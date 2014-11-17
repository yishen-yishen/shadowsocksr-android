/*
 * Shadowsocks - A shadowsocks client for Android
 * Copyright (C) 2014 <max.c.lv@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 *
 *                            ___====-_  _-====___
 *                      _--^^^#####//      \\#####^^^--_
 *                   _-^##########// (    ) \\##########^-_
 *                  -############//  |\^^/|  \\############-
 *                _/############//   (@::@)   \\############\_
 *               /#############((     \\//     ))#############\
 *              -###############\\    (oo)    //###############-
 *             -#################\\  / VV \  //#################-
 *            -###################\\/      \//###################-
 *           _#/|##########/\######(   /\   )######/\##########|\#_
 *           |/ |#/\#/\#/\/  \#/\##\  |  |  /##/\#/  \/\#/\#/\#| \|
 *           `  |/  V  V  `   V  \#\| |  | |/#/  V   '  V  V  \|  '
 *              `   `  `      `   / | |  | | \   '      '  '   '
 *                               (  | |  | |  )
 *                              __\ | |  | | /__
 *                             (vvv(VVV)(VVV)vvv)
 *
 *                              HERE BE DRAGONS
 *
 */

package com.github.shadowsocks

import java.io.File
import java.lang.reflect.{InvocationTargetException, Method}
import java.util.{Timer, TimerTask}

import android.app.{Notification, NotificationManager, PendingIntent, Service}
import android.content._
import android.content.pm.{PackageInfo, PackageManager}
import android.os._
import android.support.v4.app.NotificationCompat
import android.util.Log
import com.github.shadowsocks.aidl.Config
import com.github.shadowsocks.utils._
import com.google.android.gms.analytics.HitBuilders
import org.apache.http.conn.util.InetAddressUtils

import scala.collection._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ops._

class ShadowsocksNatService extends Service with BaseService {

  val TAG = "ShadowsocksNatService"

  val CMD_IPTABLES_RETURN = " -t nat -A OUTPUT -p tcp -d 0.0.0.0 -j RETURN"
  val CMD_IPTABLES_DNAT_ADD_SOCKS = " -t nat -A OUTPUT -p tcp " +
    "-j DNAT --to-destination 127.0.0.1:8123"
  val DNS_PORT = 8153

  private val mStartForegroundSignature = Array[Class[_]](classOf[Int], classOf[Notification])
  private val mStopForegroundSignature = Array[Class[_]](classOf[Boolean])
  private val mSetForegroundSignature = Array[Class[_]](classOf[Boolean])

  var receiver: BroadcastReceiver = null
  var notificationManager: NotificationManager = null
  var config: Config = null
  var apps: Array[ProxiedApp] = null
  val myUid = Process.myUid()

  private var mSetForeground: Method = null
  private var mStartForeground: Method = null
  private var mStopForeground: Method = null
  private var mSetForegroundArgs = new Array[AnyRef](1)
  private var mStartForegroundArgs = new Array[AnyRef](2)
  private var mStopForegroundArgs = new Array[AnyRef](1)

  private var lastTxRate = 0
  private var lastRxRate = 0
  private var timer: Timer = null
  private val TIMER_INTERVAL = 2

  private lazy val application = getApplication.asInstanceOf[ShadowsocksApplication]

  def startShadowsocksDaemon() {
    if (config.route != Route.ALL) {
      val acl: Array[String] = config.route match {
        case Route.BYPASS_LAN => getResources.getStringArray(R.array.private_route)
        case Route.BYPASS_CHN => getResources.getStringArray(R.array.chn_route_full)
      }
      ConfigUtils.printToFile(new File(Path.BASE + "acl.list"))(p => {
        acl.foreach(item => p.println(item))
      })
    }

    val cmd = new ArrayBuffer[String]
    cmd += ("ss-local"
          , "-b" , "127.0.0.1"
          , "-s" , config.proxy
          , "-p" , config.remotePort.toString
          , "-l" , config.localPort.toString
          , "-k" , config.sitekey
          , "-m" , config.encMethod
          , "-f" , Path.BASE + "ss-local.pid")

    if (config.route != Route.ALL) {
      cmd += "--acl"
      cmd += (Path.BASE + "acl.list")
    }

    if (BuildConfig.DEBUG) Log.d(TAG, cmd.mkString(" "))

    Core.sslocal(cmd.toArray)
  }

  def startDnsDaemon() {
    if (config.isUdpDns) {
      val cmd = new ArrayBuffer[String]
      cmd += ("ss-tunnel" , "-u"
            , "-b" , "127.0.0.1"
            , "-l" , "8153" 
            , "-L" , "8.8.8.8:53"
            , "-s" , config.proxy
            , "-p" , config.remotePort.toString
            , "-k" , config.sitekey
            , "-m" , config.encMethod
            , "-f" , Path.BASE + "ss-tunnel.pid")
      if (BuildConfig.DEBUG) Log.d(TAG, cmd.mkString(" "))
      Core.sstunnel(cmd.toArray)
    } else {
      val cmdBuf = new ArrayBuffer[String]
      cmdBuf += ("ss-tunnel"
        , "-b", "127.0.0.1"
        , "-l", "8163"
        , "-L", "8.8.8.8:53"
        , "-s", config.proxy
        , "-p", config.remotePort.toString
        , "-k", config.sitekey
        , "-m", config.encMethod
        , "-f", Path.BASE + "ss-tunnel.pid")
      if (BuildConfig.DEBUG) Log.d(TAG, cmdBuf.mkString(" "))
      Core.sstunnel(cmdBuf.toArray)

      val conf = ConfigUtils
        .PDNSD_BYPASS.format("127.0.0.1", getString(R.string.exclude), 8163)
      ConfigUtils.printToFile(new File(Path.BASE + "pdnsd.conf"))(p => {
         p.println(conf)
      })
      val cmd = Path.BASE + "pdnsd -c " + Path.BASE + "pdnsd.conf"
      if (BuildConfig.DEBUG) Log.d(TAG, cmd)
      Core.pdnsd(cmd.split(" "))
    }
  }

  def getVersionName: String = {
    var version: String = null
    try {
      val pi: PackageInfo = getPackageManager.getPackageInfo(getPackageName, 0)
      version = pi.versionName
    } catch {
      case e: PackageManager.NameNotFoundException =>
        version = "Package name not found"
    }
    version
  }

  def startRedsocksDaemon() {
    val conf = ConfigUtils.REDSOCKS.format(config.localPort)
    val args = "redsocks -p %sredsocks.pid -c %sredsocks.conf"
      .format(Path.BASE, Path.BASE)
    ConfigUtils.printToFile(new File(Path.BASE + "redsocks.conf"))(p => {
      p.println(conf)
    })
    val cmd = Path.BASE + args
    Console.runCommand(cmd)
  }

  /** Called when the activity is first created. */
  def handleConnection: Boolean = {

    startDnsDaemon()
    startRedsocksDaemon()
    startShadowsocksDaemon()
    setupIptables()
    flushDNS()

    true
  }

  def invokeMethod(method: Method, args: Array[AnyRef]) {
    try {
      method.invoke(this, mStartForegroundArgs: _*)
    } catch {
      case e: InvocationTargetException =>
        Log.w(TAG, "Unable to invoke method", e)
      case e: IllegalAccessException =>
        Log.w(TAG, "Unable to invoke method", e)
    }
  }

  def notifyForegroundAlert(title: String, info: String) {
    val openIntent = new Intent(this, classOf[Shadowsocks])
    openIntent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    val contentIntent = PendingIntent.getActivity(this, 0, openIntent, 0)
    val closeIntent = new Intent(Action.CLOSE)
    val actionIntent = PendingIntent.getBroadcast(this, 0, closeIntent, 0)
    val builder = new NotificationCompat.Builder(this)

    builder
      .setWhen(0)
      .setTicker(title)
      .setContentTitle(getString(R.string.app_name))
      .setContentText(info)
      .setContentIntent(contentIntent)
      .setSmallIcon(R.drawable.ic_stat_shadowsocks)
      .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop),
        actionIntent)

    startForegroundCompat(1, builder.build)
  }

  def onBind(intent: Intent): IBinder = {
    Log.d(TAG, "onBind")
    if (Action.SERVICE == intent.getAction) {
      binder
    } else {
      null
    }
  }

  override def onCreate() {
    super.onCreate()

    ConfigUtils.refresh(this)

    notificationManager = this
      .getSystemService(Context.NOTIFICATION_SERVICE)
      .asInstanceOf[NotificationManager]
    try {
      mStartForeground = getClass.getMethod("startForeground", mStartForegroundSignature: _*)
      mStopForeground = getClass.getMethod("stopForeground", mStopForegroundSignature: _*)
    } catch {
      case e: NoSuchMethodException =>
        mStartForeground = {
          mStopForeground = null
          mStopForeground
        }
    }
    try {
      mSetForeground = getClass.getMethod("setForeground", mSetForegroundSignature: _*)
    } catch {
      case e: NoSuchMethodException =>
        throw new IllegalStateException(
          "OS doesn't have Service.startForeground OR Service.setForeground!")
    }
  }

  def killProcesses() {
    Console.runRootCommand(Utils.getIptables + " -t nat -F OUTPUT")

    val ab = new ArrayBuffer[String]

    ab.append("kill -9 `cat " + Path.BASE + "ss-tunnel.pid`")
    ab.append("kill -9 `cat " + Path.BASE +"redsocks.pid`")
    ab.append("killall -9 redsocks")
    ab.append("kill -15 `cat " + Path.BASE + "pdnsd.pid`")
    ab.append("killall -15 pdnsd")

    Console.runRootCommand(ab.toArray)

    ab.clear()
    ab.append("kill -9 `cat " + Path.BASE + "ss-local.pid`")

    Console.runCommand(ab.toArray)
  }

  def flushDNS() {
    Console.runRootCommand(Array("ndc resolver flushdefaultif", "ndc resolver flushif wlan0"))
  }

  def setupIptables() = {
    val init_sb = new ArrayBuffer[String]
    val http_sb = new ArrayBuffer[String]

    init_sb.append(Utils.getIptables + " -t nat -F OUTPUT")

    val cmd_bypass = Utils.getIptables + CMD_IPTABLES_RETURN
    if (!InetAddressUtils.isIPv6Address(config.proxy.toUpperCase)) {
      init_sb.append(cmd_bypass.replace("-d 0.0.0.0", "-d " + config.proxy))
    }
    init_sb.append(cmd_bypass.replace("0.0.0.0", "127.0.0.1"))
    init_sb.append(cmd_bypass.replace("-d 0.0.0.0", "-m owner --uid-owner " + myUid))

    init_sb.append(Utils.getIptables
      + " -t nat -A OUTPUT -p udp --dport 53 -j DNAT --to-destination 127.0.0.1:" + DNS_PORT)
    if (config.isGlobalProxy || config.isBypassApps) {
      http_sb.append(Utils.getIptables + CMD_IPTABLES_DNAT_ADD_SOCKS)
    }
    if (!config.isGlobalProxy) {
      if (apps == null || apps.length <= 0) {
        apps = AppManager.getProxiedApps(this, config.proxiedAppString)
      }
      val uidSet: mutable.HashSet[Int] = new mutable.HashSet[Int]
      for (app <- apps) {
        if (app.proxied) {
          uidSet.add(app.uid)
        }
      }
      for (uid <- uidSet) {
        if (!config.isBypassApps) {
          http_sb.append((Utils.getIptables + CMD_IPTABLES_DNAT_ADD_SOCKS).replace("-t nat", "-t nat -m owner --uid-owner " + uid))
        } else {
          init_sb.append(cmd_bypass.replace("-d 0.0.0.0", "-m owner --uid-owner " + uid))
        }
      }
    }
    Console.runRootCommand(init_sb.toArray)
    Console.runRootCommand(http_sb.toArray)
  }

  /**
   * This is a wrapper around the new startForeground method, using the older
   * APIs if it is not available.
   */
  def startForegroundCompat(id: Int, notification: Notification) {
    if (mStartForeground != null) {
      mStartForegroundArgs(0) = int2Integer(id)
      mStartForegroundArgs(1) = notification
      invokeMethod(mStartForeground, mStartForegroundArgs)
      return
    }
    mSetForegroundArgs(0) = boolean2Boolean(x = true)
    invokeMethod(mSetForeground, mSetForegroundArgs)
    notificationManager.notify(id, notification)
  }

  /**
   * This is a wrapper around the new stopForeground method, using the older
   * APIs if it is not available.
   */
  def stopForegroundCompat(id: Int) {
    if (mStopForeground != null) {
      mStopForegroundArgs(0) = boolean2Boolean(x = true)
      try {
        mStopForeground.invoke(this, mStopForegroundArgs: _*)
      } catch {
        case e: InvocationTargetException =>
          Log.w(TAG, "Unable to invoke stopForeground", e)
        case e: IllegalAccessException =>
          Log.w(TAG, "Unable to invoke stopForeground", e)
      }
      return
    }
    notificationManager.cancel(id)
    mSetForegroundArgs(0) = boolean2Boolean(x = false)
    invokeMethod(mSetForeground, mSetForegroundArgs)
  }

  override def startRunner(c: Config) {

    config = c

    // register close receiver
    val filter = new IntentFilter()
    filter.addAction(Intent.ACTION_SHUTDOWN)
    filter.addAction(Action.CLOSE)
    receiver = new BroadcastReceiver() {
      def onReceive(p1: Context, p2: Intent) {
        stopRunner()
      }
    }
    registerReceiver(receiver, filter)


    // send event
    application.tracker.send(new HitBuilders.EventBuilder()
      .setCategory(TAG)
      .setAction("start")
      .setLabel(getVersionName)
      .build())

    changeState(State.CONNECTING)

    spawn {

      if (config.proxy == "198.199.101.152") {
        val holder = application.containerHolder
        try {
          config = ConfigUtils.getPublicConfig(getBaseContext, holder.getContainer, config)
        } catch {
          case ex: Exception =>
            changeState(State.STOPPED, getString(R.string.service_failed))
            stopRunner()
            config = null
        }
      }

      if (config != null) {

        killProcesses()

        var resolved: Boolean = false
        if (!InetAddressUtils.isIPv4Address(config.proxy) &&
          !InetAddressUtils.isIPv6Address(config.proxy)) {
          Utils.resolve(config.proxy, enableIPv6 = true) match {
            case Some(a) =>
              config.proxy = a
              resolved = true
            case None => resolved = false
          }
        } else {
          resolved = true
        }

        if (resolved && handleConnection) {
          notifyForegroundAlert(getString(R.string.forward_success),
            getString(R.string.service_running).format(config.profileName))
          changeState(State.CONNECTED)
        } else {
          changeState(State.STOPPED, getString(R.string.service_failed))
          stopRunner()
        }
      }
    }
  }

  override def stopRunner() {

    // change the state
    changeState(State.STOPPED)

    // send event
    application.tracker.send(new HitBuilders.EventBuilder()
      .setCategory(TAG)
      .setAction("stop")
      .setLabel(getVersionName)
      .build())

    // reset timer
    if (timer != null) {
      timer.cancel()
      timer = null
    }

    // reset NAT
    killProcesses()

    // stop the service if no callback registered
    if (callbackCount == 0) {
      stopSelf()
    }

    // clean up context
    if (receiver != null) {
      unregisterReceiver(receiver)
      receiver = null
    }

    stopForegroundCompat(1)
  }

  override def stopBackgroundService() {
    stopRunner()
  }

  override def getTag = TAG
  override def getServiceMode = Mode.NAT
  override def getContext = getBaseContext
}
