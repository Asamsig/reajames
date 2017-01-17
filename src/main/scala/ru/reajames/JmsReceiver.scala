package ru.reajames

import Jms._
import javax.jms._
import org.reactivestreams._
import scala.annotation.tailrec
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

/**
  * Represents a publisher in terms of reactive streams.
  * @author Dmitry Dobrynin <dobrynya@inbox.ru>
  *         Created at 21.12.16 0:14.
  */
class JmsReceiver(connectionFactory: ConnectionFactory, destinationFactory: DestinationFactory,
                  credentials: Option[(String, String)] = None, clientId: Option[String] = None)
                 (implicit executionContext: ExecutionContext) extends Publisher[Message] with Logging {

  def subscribe(subscriber: Subscriber[_ >: Message]): Unit = {
    if (subscriber == null)
      throw new NullPointerException("Subscriber should be specified!")

    // TODO: Implement asynchronous initialization!
    val subscription = for {
      c <- connection(connectionFactory, credentials, clientId)
      _ <- start(c)
      s <- session(c)
      d <- destination(s, destinationFactory)
      consumer <- consumer(s, d)
    } yield new Subscription {
      val cancelled = new AtomicBoolean(false)
      val requested = new AtomicLong(0)

      def cancel(): Unit =
        if (cancelled.compareAndSet(false, true)) {
          if (requested.get() == 0) subscriber.onComplete() // no receiving thread so explicitly complete subscriber
          close(consumer).recover {
            case th => logger.warn("An error occurred during closing consumer!", th)
          }
          close(c).recover {
            case th => logger.warn("An error occurred during closing connection!", th)
          }
          logger.debug("Cancelled subscription to {}", destinationFactory)
        }

      @tailrec
      def receiveMessage(): Unit = {
          receive(consumer).map {
            case Some(msg) =>
              logger.debug("Received message {}", msg)
              subscriber.onNext(msg)
            case None =>
              logger.debug("Consumer possibly has been closed, completing subscriber")
              subscriber.onComplete()
          } recover {
            case th =>
              cancel()
              subscriber.onError(th)
          }
          if (requested.decrementAndGet() > 0 && !cancelled.get()) receiveMessage()
        }

      def request(n: Long): Unit = {
        logger.debug("Requested {} from {}", n, destinationFactory)
        if (n <= 0)
          throw new IllegalArgumentException(s"Wrong requested items amount $n!")
        else if (requested.getAndAdd(n) == 0)
          executionContext.execute(() => receiveMessage())
      }

      override def toString: String = "JmsReceiver(%s,%s)".format(c, destinationFactory)
    }

    subscription match {
      case Success(s) =>
        logger.debug("Subscribed to {}", destinationFactory)
        subscriber.onSubscribe(s)
      case Failure(th) =>
        logger.warn(s"Could not subscribe to $destinationFactory", th)
        subscriber.onError(th)
    }
  }
}
