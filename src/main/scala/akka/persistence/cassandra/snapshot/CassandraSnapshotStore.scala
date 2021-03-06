package akka.persistence.cassandra.snapshot

import java.lang.{ Long => JLong }
import java.nio.ByteBuffer

import akka.persistence.snapshot.SnapshotStore

import scala.collection.immutable
import scala.concurrent.Future

import akka.actor._
import akka.persistence._
import akka.persistence.cassandra._
import akka.persistence.serialization.Snapshot
import akka.serialization.SerializationExtension

import com.datastax.driver.core._
import com.datastax.driver.core.utils.Bytes

abstract class CassandraSnapshotStore extends SnapshotStore with CassandraStatements with ActorLogging {

  this: PluginConfiguration =>

  private val systemConfig = context.system.settings.config

  val config = new CassandraSnapshotStoreConfig(pluginConfig(systemConfig))
  val serialization = SerializationExtension(context.system)

  import context.dispatcher
  import config._

  val cluster = clusterBuilder.build
  val session = cluster.connect()

  if (config.keyspaceAutoCreate) {
    retry(config.keyspaceAutoCreateRetries) {
      session.execute(createKeyspace)
    }
  }
  session.execute(createTable)

  val preparedWriteSnapshot = session.prepare(writeSnapshot).setConsistencyLevel(writeConsistency)
  val preparedDeleteSnapshot = session.prepare(deleteSnapshot).setConsistencyLevel(writeConsistency)
  val preparedSelectSnapshot = session.prepare(selectSnapshot).setConsistencyLevel(readConsistency)

  val preparedSelectSnapshotMetadataForLoad =
    session.prepare(selectSnapshotMetadata(limit = Some(maxMetadataResultSize))).setConsistencyLevel(readConsistency)

  val preparedSelectSnapshotMetadataForDelete =
    session.prepare(selectSnapshotMetadata(limit = None)).setConsistencyLevel(readConsistency)

  def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] = for {
    mds <- Future(metadata(persistenceId, criteria).take(3).toVector)
    res <- loadNAsync(mds)
  } yield res

  def loadNAsync(metadata: immutable.Seq[SnapshotMetadata]): Future[Option[SelectedSnapshot]] = metadata match {
    case Seq() => Future.successful(None)
    case md +: mds => load1Async(md) map {
      case Snapshot(s) => Some(SelectedSnapshot(md, s))
    } recoverWith {
      case e => loadNAsync(mds) // try older snapshot
    }
  }

  def load1Async(metadata: SnapshotMetadata): Future[Snapshot] = {
    val stmt = preparedSelectSnapshot.bind(metadata.persistenceId, metadata.sequenceNr: JLong)
    session.executeAsync(stmt).map(rs => deserialize(rs.one().getBytes("snapshot")))
  }

  def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = {
    val stmt = preparedWriteSnapshot.bind(metadata.persistenceId, metadata.sequenceNr: JLong, metadata.timestamp: JLong, serialize(Snapshot(snapshot)))
    session.executeAsync(stmt).map(_ => ())
  }

  def deleteAsync(metadata: SnapshotMetadata): Future[Unit] = {
    val stmt = preparedDeleteSnapshot.bind(metadata.persistenceId, metadata.sequenceNr: JLong)
    session.executeAsync(stmt).map(_ => ())
  }

  def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] = for {
    mds <- Future(metadata(persistenceId, criteria).toVector)
    res <- executeBatch(batch => mds.foreach(md => batch.add(preparedDeleteSnapshot.bind(md.persistenceId, md.sequenceNr: JLong))))
  } yield res

  def executeBatch(body: BatchStatement ⇒ Unit): Future[Unit] = {
    val batch = new BatchStatement().setConsistencyLevel(writeConsistency).asInstanceOf[BatchStatement]
    body(batch)
    session.executeAsync(batch).map(_ => ())
  }

  private def serialize(snapshot: Snapshot): ByteBuffer =
    ByteBuffer.wrap(serialization.findSerializerFor(snapshot).toBinary(snapshot))

  private def deserialize(bytes: ByteBuffer): Snapshot =
    serialization.deserialize(Bytes.getArray(bytes), classOf[Snapshot]).get

  private def metadata(persistenceId: String, criteria: SnapshotSelectionCriteria): Iterator[SnapshotMetadata] =
    new RowIterator(persistenceId, criteria.maxSequenceNr).map { row =>
      SnapshotMetadata(row.getString("persistence_id"), row.getLong("sequence_nr"), row.getLong("timestamp"))
    }.dropWhile(_.timestamp > criteria.maxTimestamp)

  private class RowIterator(persistenceId: String, maxSequenceNr: Long) extends Iterator[Row] {
    var currentSequenceNr = maxSequenceNr
    var rowCount = 0
    var iter = newIter()

    def newIter() = session.execute(selectSnapshotMetadata(Some(maxMetadataResultSize)), persistenceId, currentSequenceNr: JLong).iterator

    @annotation.tailrec
    final def hasNext: Boolean =
      if (iter.hasNext)
        true
      else if (rowCount < maxMetadataResultSize)
        false
      else {
        rowCount = 0
        currentSequenceNr -= 1
        iter = newIter()
        hasNext
      }

    def next(): Row = {
      val row = iter.next()
      currentSequenceNr = row.getLong("sequence_nr")
      rowCount += 1
      row
    }
  }

  override def postStop(): Unit = {
    session.close()
    cluster.close()
  }
}
