import com.ergodicity.marketdb.core._
import com.twitter.ostrich.admin.config.{StatsConfig, JsonStatsLoggerConfig, TimeSeriesCollectorConfig}

new MarketDBConfig {
  admin.httpPort = 9000

  tradesTable = "test-market-trades"
  ordersTable = "test-market-orders"
  uidTable = "test-market-uid"

  val kestrelLoaderService = (marketDB: MarketDB) => {
    new KestrelLoader(marketDB, KestrelConfig(Seq("localhost:22133"), "trades", "orders", 30))
  }

  services = Seq(kestrelLoaderService)

  admin.statsNodes = new StatsConfig {
    reporters = new JsonStatsLoggerConfig {
      loggerName = "stats"
      serviceName = "marketDB"
    } :: new TimeSeriesCollectorConfig
  }
}
