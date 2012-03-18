package com.ergodicity.marketdb.core

import org.slf4j.LoggerFactory
import org.zeromq.ZMQ.Context
import com.twitter.util.FuturePool
import com.ergodicity.zeromq.SocketType._
import java.util.UUID
import com.ergodicity.marketdb.stream._
import org.joda.time.Interval
import com.ergodicity.marketdb.model.{Market, Code}
import concurrent.stm._
import com.twitter.conversions.time._
import com.ergodicity.zeromq._
import com.twitter.concurrent.{Broker, Offer}
import org.zeromq.{ZMQForwarder, ZMQ}

trait MarketStreamer extends MarketService

class TradesStreamer(marketDb: MarketDB, controlEndpoint: String, publishEndpoint: String, heartbeatRef: HeartbeatRef)
                    (implicit context: Context, pool: FuturePool) extends MarketStreamer {
  val log = LoggerFactory.getLogger(classOf[TradesStreamer])

  private var connectedTradeStreamer: ConnectedTradesStreamer = _

  def start() {
    log.info("Start TradesStreamer")
    connectedTradeStreamer = new ConnectedTradesStreamer(marketDb, controlEndpoint, publishEndpoint, heartbeatRef)
  }

  def shutdown() {
    log.info("Shutdown TradesStreamer")
    connectedTradeStreamer.shutdown()
  }
}

private[this] class ConnectedTradesStreamer(marketDb: MarketDB, controlEndpoint: String, publishEndpoint: String, heartbeatRef: HeartbeatRef)
                                           (implicit context: Context, pool: FuturePool) {
  val log = LoggerFactory.getLogger(classOf[ConnectedTradesStreamer])

  val InputEndpoint = "inproc://trades-stream"

  import MarketStreamProtocol._

  private val tradeScanners = Ref(Map[String, (TradesScanner, Option[Offer[Unit]])]())

  // Start beating
  val heartbeat = new Heartbeat(heartbeatRef, duration = 3.second, lossLimit = 3)
  heartbeat.start

  // Configure ZMQ.Forwarder
  val input = context.socket(ZMQ.SUB)
  input.bind(InputEndpoint)
  input.subscribe(Array[Byte]())
  val output = context.socket(ZMQ.PUB)
  output.bind(publishEndpoint)

  val forwarder = new ZMQForwarder(context, input, output)
  val forwarding = pool(forwarder.run()) onSuccess {_=>
    log.error("Forwarder unexpectedly finished")
    shutdown()    
  } onFailure {err =>
    log.error("Forwarder failed: "+err)
    shutdown()
  }

  // Control socket
  val control = Client(Rep, options = Bind(controlEndpoint) :: Nil)
  val controlHandle = control.read[StreamControlMessage]
  controlHandle.messages foreach {
    case OpenStream(market, code, interval) => control.send[StreamControlMessage](openStream(market, code, interval))
    case CloseStream(stream) => control.send[StreamControlMessage](closeStream(stream))
    case msg => log.error("Unknown StreamControllMessage: " + msg)
  }

  private def openStream(market: Market, code: Code, interval: Interval) = {
    log.info("Open trades stream; Market=" + market + ", code=" + code + ", interval=" + interval)

    val id = UUID.randomUUID().toString
    val streamIdentifier = StreamIdentifier(id)
    val scanner = marketDb.scan(market, code, interval)()
    val tradesScanner = TradesScanner(scanner)

    def startStreaming() {
      log.info("Start trades streaming: "+id)
      val pub = Client(Pub, options = Connect(InputEndpoint) :: Nil)
      val handle = tradesScanner.open()

      val stop = new Broker[Unit]
      Offer.select(
        stop.recv {_ =>
          log.info("Stop streaming: "+id)
          handle.close()
          pub.close()
        }
      )
      val stopOffer = stop.send(())

      atomic {implicit txt =>
        tradeScanners.transform(_ + (id ->(tradesScanner, Some(stopOffer))))
      }

      handle.trades foreach {r =>
        pub.send[StreamPayloadMessage](Trades(r.payload))
        r.ack()
      }

      handle.error foreach {
        case TradesScanCompleted =>
          log.info("Trades scan completed")
          pub.send[StreamPayloadMessage](Completed())
          closeStream(streamIdentifier)
        case err =>
          log.error("Got error: "+err)
          pub.send[StreamPayloadMessage](Broken(err.getMessage))
          closeStream(streamIdentifier)
      }
    }

    atomic {implicit txn =>
      tradeScanners.transform(_ + (id -> (tradesScanner, None)))
    }

    heartbeat.track(Identifier(id)) foreach {
      case Connected =>
        log.info("Connected client for trades stream id=" + id)
        startStreaming()

      case Lost =>
        log.info("Lost client for stream id=" + id);
        closeStream(streamIdentifier)
    }

    StreamOpened(streamIdentifier)
  }

  private def closeStream(stream: StreamIdentifier) = {
    log.info("Close trades stream: "+stream)
    val id = stream.id
    tradeScanners.single() get(id) foreach {_._2 foreach {_()}}
    atomic {implicit txt =>
      tradeScanners.transform(_ - id)
    }
    StreamClosed()
  }

  def shutdown() {
    log.info("Shutdown TradesStreamer")
    // Close each opened scanner
    tradeScanners.single().values.foreach {_._2 foreach {_()}}
    // Shutdown heartbeat
    heartbeat.stop()
    // Shutdown forwarder
    input.close()
    output.close();
    forwarding.cancel()
    // Shutdown control
    controlHandle.close()
    control.close()
  }
}

