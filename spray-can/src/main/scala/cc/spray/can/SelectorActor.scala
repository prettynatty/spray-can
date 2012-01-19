package cc.spray.can
import akka.actor.Actor
import java.nio.channels.spi.AbstractSelector

class SelectorActor(val selector: AbstractSelector) extends Actor {
  def receive = {
    case Select =>
      selector.select()
      sender ! Selected
  }
}
