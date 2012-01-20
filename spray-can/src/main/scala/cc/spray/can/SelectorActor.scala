package cc.spray.can
import akka.actor.{Actor, ActorLogging}
import java.nio.channels.spi.AbstractSelector

class SelectorActor(val selector: AbstractSelector) extends Actor with ActorLogging {
  def receive = {
    case Select =>
      log.debug("SelectorActor#receive")
      try {
        selector.select()
        log.debug("selector.select()")
        sender ! Selected
        log.debug("SelectorActor#receive - exit")
        } catch {
          case e => log.error("Died in SelectorActor#receive with {}",e)
        }
  }
}
