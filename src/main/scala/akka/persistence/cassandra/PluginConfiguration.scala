package akka.persistence.cassandra

trait PluginConfiguration {
  def configurationKey: String
}

trait DefaultJournalPluginConfiguration extends PluginConfiguration {
  override def configurationKey: String = "cassandra-journal"
}

trait DefaultSnapshotPluginConfiguration extends PluginConfiguration {
  override def configurationKey: String = "cassandra-snapshot-store"
}