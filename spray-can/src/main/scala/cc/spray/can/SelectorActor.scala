package cc.spray.can
import akka.actor.{Actor, ActorLogging}
import java.nio.channels.spi.AbstractSelector

class SelectorActor(val selector: AbstractSelector) extends Actor with ActorLogging {
  def receive = {
    case Select =>
      log.info("SelectorActor#receive")
      try {
        selector.select()
        log.info("selector.select()")
        sender ! Selected
        log.info("SelectorActor#receive - exit")
        } catch {
          case e => log.error("Died in SelectorActor#receive with {}",e)
        }
  }
}
