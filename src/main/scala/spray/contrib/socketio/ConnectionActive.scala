package spray.contrib.socketio

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Cancellable
import akka.actor.Props
import akka.event.LoggingAdapter
import akka.util.ByteString
import org.parboiled2.ParseError
import scala.collection.immutable
import scala.concurrent.duration._
import spray.can.websocket.frame.TextFrame
import spray.contrib.socketio
import spray.contrib.socketio.packet.ConnectPacket
import spray.contrib.socketio.packet.DisconnectPacket
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.HeartbeatPacket
import spray.contrib.socketio.packet.JsonPacket
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.packet.Packet
import spray.contrib.socketio.packet.PacketParser
import spray.contrib.socketio.transport.Transport
import spray.http.HttpOrigin
import spray.http.Uri

class GeneralConnectionActiveResolver extends Actor with ActorLogging {
  import context.dispatcher

  def receive = {
    case ConnectionActive.CreateSession(sessionId: String) =>
      context.child(sessionId) match {
        case Some(_) =>
        case None    => context.actorOf(Props(classOf[GeneralConnectionActive]), name = sessionId)
      }

    case cmd: ConnectionActive.Command =>
      context.child(cmd.sessionId) match {
        case Some(ref) => ref forward cmd
        case None      => log.warning("Failed to select actor {}", cmd.sessionId)
      }
  }
}

object ConnectionActive {

  val actorResolveTimeout = socketio.config.getInt("server.actor-selection-resolve-timeout")
  val shardName: String = "connectionActives"

  case object AskConnectedTime

  case object Pause
  case object Awake

  sealed trait Command {
    def sessionId: String
  }

  final case class CreateSession(sessionId: String) extends Command

  final case class Connecting(sessionId: String, query: Uri.Query, origins: Seq[HttpOrigin], transportConnection: ActorRef, transport: Transport) extends Command

  final case class SendMessage(sessionId: String, endpoint: String, msg: String) extends Command
  final case class SendJson(sessionId: String, endpoint: String, json: String) extends Command
  final case class SendEvent(sessionId: String, endpoint: String, name: String, args: Either[String, Seq[String]]) extends Command
  final case class SendPackets(sessionId: String, packets: Seq[Packet]) extends Command

  final case class OnPayload(sessionId: String, transportConnection: ActorRef, payload: ByteString) extends Command
  final case class OnGet(sessionId: String, transportConnection: ActorRef) extends Command
  final case class OnPost(sessionId: String, transportConnection: ActorRef, payload: ByteString) extends Command
  final case class OnFrame(sessionId: String, transportConnection: ActorRef, frame: TextFrame) extends Command

  sealed trait Event extends Serializable
  final case class Connected(sessionId: String, query: Uri.Query, origins: Seq[HttpOrigin], transportConnection: ActorRef, transport: Transport) extends Event
  final case class UpdatePackets(packets: Seq[Packet]) extends Event
}


class GeneralConnectionActive extends ConnectionActive with Actor with ActorLogging {

  // have to call after log created
  enableCloseTimeout()

  def receive = working
}

/**
 *
 * transportConnection <1..n--1> connectionActive <1--1> connContext <1--n> transport
 */
trait ConnectionActive { actor: Actor =>
  import ConnectionActive._
  import context.dispatcher

  def log: LoggingAdapter

  var connectionContext: Option[ConnectionContext] = None
  var transportConnection: ActorRef = _

  var closeTimeout: Option[Cancellable] = None

  // It seems socket.io client may fire heartbeat only when it received heartbeat
  // from server, or, just bounce heartheat instead of firing heartbeat standalone.
  var heartbeatHandler: Option[Cancellable] = None
  var heartbeatTimeout: Option[Cancellable] = None

  var pendingPackets = immutable.Queue[Packet]()

  val startTime = System.currentTimeMillis

  val heartbeatInterval = socketio.Settings.HeartbeatTimeout * 0.618

  def connected() {
    sendPacket(ConnectPacket())

    enableHeartbeat()
    resetHeartbeatTimeout()
  }

  def update(event: Event) = {
    event match {
      case x: Connected =>
        connectionContext = Some(new ConnectionContext(x.sessionId, x.query, x.origins))
        transportConnection = x.transportConnection
        connectionContext.foreach(_.bindTransport(x.transport))
      case x: UpdatePackets =>
        pendingPackets = immutable.Queue(x.packets: _*)
    }
  }

  def processNewConnected(conn: Connected) {
    update(conn)
    connected()
  }

  def processUpdatePackets(packets: UpdatePackets) {
    update(packets)
  }

  def working: Receive = {
    case CreateSession(_) => // may be forwarded by resolver, just ignore it.

    case conn @ Connecting(sessionId, query, origins, transportConn, transport) =>
      log.debug("Connecting request: {}", sessionId)
      disableCloseTimeout()

      connectionContext match {
        case Some(existed) =>
          transportConnection = transportConn
          existed.bindTransport(transport)
          connected()
        case None =>
          processNewConnected(Connected(conn.sessionId, conn.query, conn.origins, conn.transportConnection, conn.transport))
      }

    case Pause =>

    case OnFrame(sessionId, transportConnection, frame) => onFrame(transportConnection, frame)
    case OnGet(sessionId, transportConnection) => onGet(transportConnection)
    case OnPost(sessionId, transportConnection, payload) => onPost(transportConnection, payload)

    case SendMessage(sessionId, endpoint, msg) => sendMessage(endpoint, msg)
    case SendJson(sessionId, endpoint, json) => sendJson(endpoint, json)
    case SendEvent(sessionId, endpoint, name, args) => sendEvent(endpoint, name, args)
    case SendPackets(sessionId, packets) => sendPacket(packets: _*)

    case AskConnectedTime =>
      sender() ! System.currentTimeMillis - startTime
  }

  def disableHeartbeat() {
    log.debug("{}: heartbeat disabled.", self.path)
    heartbeatHandler foreach (_.cancel)
    heartbeatHandler = None
  }

  def enableHeartbeat() {
    log.debug("{}: heartbeat enabled.", self.path)
    if (heartbeatHandler.isEmpty || heartbeatHandler.nonEmpty && heartbeatHandler.get.isCancelled) {
      heartbeatHandler = Some(context.system.scheduler.schedule(0.seconds, heartbeatInterval.seconds, self, SendPackets(null, Array(HeartbeatPacket))))
    }
  }

  // --- heartbeat timeout

  def resetHeartbeatTimeout() {
    heartbeatTimeout foreach (_.cancel)
    heartbeatTimeout = Some(context.system.scheduler.scheduleOnce(socketio.Settings.HeartbeatTimeout.seconds) {
      enableCloseTimeout()
    })
  }

  // --- close timeout

  def enableCloseTimeout() {
    log.debug("{}: close timeout, will close in {} seconds", self.path, socketio.Settings.CloseTimeout)
    closeTimeout foreach (_.cancel)
    if (context != null) {
      closeTimeout = Some(context.system.scheduler.scheduleOnce(socketio.Settings.CloseTimeout.seconds) {
        log.warning("{}: stoped due to close timeout", self.path)
        disableHeartbeat()
        context.stop(self)
      })
    }
  }

  def disableCloseTimeout() {
    closeTimeout foreach (_.cancel)
    closeTimeout = None
  }

  // --- reacts

  private def onPayload(transportConnection: ActorRef, payload: ByteString) {
    try {
      PacketParser(payload) foreach onPacket
    } catch {
      case ex: ParseError => log.warning("Invalid socket.io packet: {} ...", payload.take(50).utf8String)
      case ex: Throwable  => log.warning("Exception during parse socket.io packet {}", ex.getMessage)
    }
  }

  private def onPacket(packet: Packet) {
    packet match {
      case HeartbeatPacket =>
        resetHeartbeatTimeout()

      case ConnectPacket(endpoint, args) =>
        // bounce connect packet back to client
        sendPacket(packet)
        connectionContext foreach dispatchData(packet)

      case DisconnectPacket(endpoint) =>
        connectionContext foreach dispatchData(packet)
        if (endpoint == "") {
          context.stop(self)
        }

      case _ =>
        connectionContext foreach dispatchData(packet)
    }
  }

  def dispatchData(packet: Packet)(ctx: ConnectionContext) {
    Namespace.dispatch(context.system, packet.endpoint, Namespace.OnPacket(packet, ctx))
  }

  def onFrame(transportConnection: ActorRef, frame: TextFrame) {
    disableCloseTimeout()
    onPayload(transportConnection, frame.payload)
  }

  def onGet(transportConnection: ActorRef) {
    disableCloseTimeout()
    connectionContext foreach { ctx =>
      processUpdatePackets(UpdatePackets(ctx.transport.writeSingle(ctx, transportConnection, isSendingNoopWhenEmpty = true, pendingPackets)))
    }
  }

  def onPost(transportConnection: ActorRef, payload: ByteString) {
    disableCloseTimeout()
    connectionContext foreach { ctx =>
      // response an empty entity to release POST before message processing
      ctx.transport.write(ctx, transportConnection, "")
    }
    onPayload(transportConnection, payload)
  }

  def sendMessage(endpoint: String, msg: String) {
    val packet = MessagePacket(-1L, false, Namespace.endpointFor(endpoint), msg)
    sendPacket(packet)
  }

  def sendJson(endpoint: String, json: String) {
    val packet = JsonPacket(-1L, false, Namespace.endpointFor(endpoint), json)
    sendPacket(packet)
  }

  def sendEvent(endpoint: String, name: String, args: Either[String, Seq[String]]) {
    val packet = args match {
      case Left(x)   => EventPacket(-1L, false, Namespace.endpointFor(endpoint), name, x)
      case Right(xs) => EventPacket(-1L, false, Namespace.endpointFor(endpoint), name, xs)
    }
    sendPacket(packet)
  }

  /**
   * enqueue packets, and let tranport decide if flush them or pending flush
   */
  def sendPacket(packets: Packet*) {
    var updatePendingPackets = pendingPackets
    packets foreach { packet => updatePendingPackets = updatePendingPackets.enqueue(packet) }
    log.debug("Enqueued {}, pendingPackets: {}", packets, pendingPackets)
    connectionContext foreach { ctx =>
      updatePendingPackets = ctx.transport.flushOrWait(ctx, transportConnection, updatePendingPackets)
    }
    processUpdatePackets(UpdatePackets(updatePendingPackets))
  }
}

