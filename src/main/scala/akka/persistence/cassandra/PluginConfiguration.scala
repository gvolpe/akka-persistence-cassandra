package akka.persistence.cassandra

import com.typesafe.config.Config

trait PluginConfiguration {
  def pluginConfig(systemConfig: Config): Config
}

trait DefaultJournalPluginConfiguration extends PluginConfiguration {
  override def pluginConfig(systemConfig: Config): Config = systemConfig.getConfig("cassandra-journal")
}

trait DefaultSnapshotPluginConfiguration extends PluginConfiguration {
  override def pluginConfig(systemConfig: Config): Config = systemConfig.getConfig("cassandra-snapshot-store")
}