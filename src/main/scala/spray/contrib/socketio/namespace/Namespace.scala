package spray.contrib.socketio.namespace

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.contrib.pattern.DistributedPubSubMediator
import akka.pattern.ask
import rx.lang.scala.Observer
import rx.lang.scala.Subject
import scala.reflect.runtime.universe._
import spray.contrib.socketio
import spray.contrib.socketio.ConnectionActive
import spray.contrib.socketio.ConnectionContext
import spray.contrib.socketio.packet.ConnectPacket
import spray.contrib.socketio.packet.DataPacket
import spray.contrib.socketio.packet.DisconnectPacket
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.JsonPacket
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.packet.Packet
import scala.util.{ Failure, Success }

/**
 *
 *
 *    +===serverConn===+               +===connActive===+              +====namespace===+
 *    |                |    OnFrame    |                |   OnPacket   |                |
 *    |                | ------------> |                | -----------> |                |
 *    |                | <------------ |                | <----------- |                |
 *    |                |  FrameCommand |                |  SendPackets |                |
 *    +================+               +================+              +================+
 *
 *
 *    +======node======+
 *    |       mediator----\
 *    |     /     |    |  |
 *    |    /      |    |  |
 *    | conn    conn   |  |
 *    | conn    conn   |  |---------------------------------------------------+
 *    |                |  |                  virtaul MEDIATOR                 |
 *    +================+  |---------------------------------------------------+
 *                        |       |
 *                        |       | (Namespace)
 *    +======node======+  |     +=V=============busi-node===============+
 *    |       mediator----/     | +endpointA              [busi-actors] |
 *    |     /     |    |        |   |  |   |                            |
 *    |    /      |    |        |   |  |   +roomA                       |
 *    | conn    conn   |        |   |  |      |                         |
 *    | conn    conn   |        |   |  |      |                         |
 *    | /              |        |   |  |      \---> channelA            |
 *    +=|==============+        |   |  |      \---> channleB            |
 *      |                       |   |  |                                |
 *      \                       |   |  +roomB                           |
 *       \                      |   |     |                             |
 *    +---|-------------+       |   |     |                             |
 *    |   | resolver    |       |   |     \---> channelA --> [observer]-----\
 *    +---|-------------+       |   |     \---> channelB                |   |
 *        |                     |   |                                   |   |
 *        |                     |   \---> channelA                      |   |
 *        |                     |   \---> channelB                      |   |
 *        |                     +=======================================+   |
 *        |                                                                 |
 *        |                                                                 |
 *        \-----------------------------------------------------------------/
 *
 *
 * @Note Akka can do millions of messages per second per actor per core.
 */
object Namespace {

  final case class Subscribe(observer: Observer[OnData])

  // --- Observable data
  sealed trait OnData {
    def context: ConnectionContext
    def packet: Packet

    final def endpoint = packet.endpoint
    final def sessionId = context.sessionId

    import ConnectionActive._
    def replyMessage(msg: String)(implicit resolver: ActorRef) = resolver ! SendMessage(sessionId, endpoint, msg)
    def replyJson(json: String)(implicit resolver: ActorRef) = resolver ! SendJson(sessionId, endpoint, json)
    def replyEvent(name: String, args: String)(implicit resolver: ActorRef) = resolver ! SendEvent(sessionId, endpoint, name, Left(args))
    def replyEvent(name: String, args: Seq[String])(implicit resolver: ActorRef) = resolver ! SendEvent(sessionId, endpoint, name, Right(args))
    def reply(packets: Packet*)(implicit resolver: ActorRef) = resolver ! SendPackets(sessionId, packets)
    def ack(args: String)(implicit resolver: ActorRef) = resolver ! SendAck(sessionId, packet.asInstanceOf[DataPacket], args)
    /**
     * @param room    room to broadcast
     * @param packet  packet to broadcast
     */
    def broadcast(room: String, packet: Packet)(implicit resolver: ActorRef) = resolver ! Broadcast(sessionId, room, packet)
  }
  final case class OnConnect(args: Seq[(String, String)], context: ConnectionContext)(implicit val packet: ConnectPacket) extends OnData
  final case class OnDisconnect(context: ConnectionContext)(implicit val packet: DisconnectPacket) extends OnData
  final case class OnMessage(msg: String, context: ConnectionContext)(implicit val packet: MessagePacket) extends OnData
  final case class OnJson(json: String, context: ConnectionContext)(implicit val packet: JsonPacket) extends OnData
  final case class OnEvent(name: String, args: String, context: ConnectionContext)(implicit val packet: EventPacket) extends OnData
}

/**
 * Namespace is refered to endpoint for observers and will subscribe to topic 'namespace' of mediator
 */
class Namespace(endpoint: String, mediator: ActorRef) extends Actor with ActorLogging {
  import Namespace._

  val channel = Subject[OnData]()

  private var isMediatorSubscribed: Boolean = _
  def subscribeMediatorForNamespace(action: () => Unit) = {
    if (!isMediatorSubscribed) {
      import context.dispatcher
      mediator.ask(DistributedPubSubMediator.Subscribe(socketio.topicForNamespace(endpoint), self))(socketio.actorResolveTimeout).mapTo[DistributedPubSubMediator.SubscribeAck] onComplete {
        case Success(ack) =>
          isMediatorSubscribed = true
          action()
        case Failure(ex) =>
          log.warning("Failed to subscribe to medietor on topic {}: {}", socketio.topicForNamespace(endpoint), ex.getMessage)
      }
    } else {
      action()
    }
  }

  import ConnectionActive.OnPacket
  def receive: Receive = {
    case x @ Subscribe(observer)                         => subscribeMediatorForNamespace { () => channel(observer) }

    case OnPacket(packet: ConnectPacket, connContext)    => channel.onNext(OnConnect(packet.args, connContext)(packet))
    case OnPacket(packet: DisconnectPacket, connContext) => channel.onNext(OnDisconnect(connContext)(packet))
    case OnPacket(packet: MessagePacket, connContext)    => channel.onNext(OnMessage(packet.data, connContext)(packet))
    case OnPacket(packet: JsonPacket, connContext)       => channel.onNext(OnJson(packet.json, connContext)(packet))
    case OnPacket(packet: EventPacket, connContext)      => channel.onNext(OnEvent(packet.name, packet.args, connContext)(packet))
  }

}
