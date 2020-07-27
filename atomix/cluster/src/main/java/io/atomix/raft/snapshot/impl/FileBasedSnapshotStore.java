/*
 * Copyright © 2020  camunda services GmbH (info@camunda.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package io.atomix.raft.snapshot.impl;

import io.atomix.raft.snapshot.PersistedSnapshot;
import io.atomix.raft.snapshot.PersistedSnapshotListener;
import io.atomix.raft.snapshot.PersistedSnapshotStore;
import io.atomix.raft.snapshot.ReceivedSnapshot;
import io.atomix.raft.snapshot.TransientSnapshot;
import io.atomix.utils.time.WallClockTimestamp;
import io.zeebe.util.FileUtil;
import io.zeebe.util.ZbLogger;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ConcurrentModificationException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;

public final class FileBasedSnapshotStore implements PersistedSnapshotStore {
  // first is the metadata and the second the the received snapshot count
  private static final String RECEIVING_DIR_FORMAT = "%s-%d";

  private static final Logger LOGGER = new ZbLogger(FileBasedSnapshotStore.class);

  // the root snapshotsDirectory where all snapshots should be stored
  private final Path snapshotsDirectory;
  // the root snapshotsDirectory when pending snapshots should be stored
  private final Path pendingDirectory;
  // keeps track of all snapshot modification listeners
  private final Set<PersistedSnapshotListener> listeners;

  private final SnapshotMetrics snapshotMetrics;

  private final AtomicReference<PersistedSnapshot> currentPersistedSnapshotRef;
  // used to write concurrently received snapshots in different pending directories
  private final AtomicLong receivingSnapshotStartCount;

  public FileBasedSnapshotStore(
      final SnapshotMetrics snapshotMetrics,
      final Path snapshotsDirectory,
      final Path pendingDirectory) {
    this.snapshotsDirectory = snapshotsDirectory;
    this.pendingDirectory = pendingDirectory;
    this.snapshotMetrics = snapshotMetrics;
    this.receivingSnapshotStartCount = new AtomicLong();

    this.listeners = new CopyOnWriteArraySet<>();

    // load previous snapshots
    currentPersistedSnapshotRef = new AtomicReference<>(loadLatestSnapshot(snapshotsDirectory));
  }

  private PersistedSnapshot loadLatestSnapshot(final Path snapshotDirectory) {
    PersistedSnapshot latestPersistedSnapshot = null;
    try (final var stream = Files.newDirectoryStream(snapshotDirectory)) {
      for (final var path : stream) {
        latestPersistedSnapshot = collectSnapshot(path);
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    return latestPersistedSnapshot;
  }

  private PersistedSnapshot collectSnapshot(final Path path) {
    final var optionalMeta = FileBasedSnapshotMetadata.ofPath(path);
    if (optionalMeta.isPresent()) {
      final var metadata = optionalMeta.get();
      return new FileBasedSnapshot(path, metadata);
    } else {
      LOGGER.warn("Expected snapshot file format to be %d-%d-%d-%d, but was {}", path);
    }
    return null;
  }

  @Override
  public boolean hasSnapshotId(final String id) {
    final var optLatestSnapshot = getLatestSnapshot();

    if (optLatestSnapshot.isPresent()) {
      final var snapshot = optLatestSnapshot.get();
      return snapshot.getPath().getFileName().toString().equals(id);
    }
    return false;
  }

  @Override
  public TransientSnapshot newTransientSnapshot(
      final long index, final long term, final WallClockTimestamp timestamp) {
    final var directory = buildPendingSnapshotDirectory(index, term, timestamp);
    final var fileBasedSnapshotMetadata = new FileBasedSnapshotMetadata(index, term, timestamp);
    return new FileBasedTransientSnapshot(fileBasedSnapshotMetadata, directory, this);
  }

  @Override
  public ReceivedSnapshot newReceivedSnapshot(final String snapshotId) {
    final var optMetadata = FileBasedSnapshotMetadata.ofFileName(snapshotId);
    final var metadata =
        optMetadata.orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Expected snapshot id in a format like 'index-term-timestamp', got '"
                        + snapshotId
                        + "'."));

    // to make the pending dir unique
    final var nextStartCount = receivingSnapshotStartCount.incrementAndGet();
    final var pendingDirectoryName =
        String.format(RECEIVING_DIR_FORMAT, metadata.getSnapshotIdAsString(), nextStartCount);
    final var pendingSnapshotDir = pendingDirectory.resolve(pendingDirectoryName);
    return new FileBasedReceivedSnapshot(metadata, pendingSnapshotDir, this);
  }

  @Override
  public Optional<PersistedSnapshot> getLatestSnapshot() {
    return Optional.ofNullable(currentPersistedSnapshotRef.get());
  }

  @Override
  public void purgePendingSnapshots() throws IOException {
    try (final var files = Files.list(pendingDirectory)) {
      files.filter(Files::isDirectory).forEach(this::purgePendingSnapshot);
    }
  }

  @Override
  public void addSnapshotListener(final PersistedSnapshotListener listener) {
    listeners.add(listener);
  }

  @Override
  public void removeSnapshotListener(final PersistedSnapshotListener listener) {
    listeners.remove(listener);
  }

  @Override
  public long getCurrentSnapshotIndex() {
    return getLatestSnapshot().map(PersistedSnapshot::getIndex).orElse(0L);
  }

  @Override
  public void delete() {
    currentPersistedSnapshotRef.set(null);

    try {
      LOGGER.debug("DELETE FOLDER {}", snapshotsDirectory);
      FileUtil.deleteFolder(snapshotsDirectory);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    try {
      LOGGER.debug("DELETE FOLDER {}", pendingDirectory);
      FileUtil.deleteFolder(pendingDirectory);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void observeSnapshotSize(final PersistedSnapshot persistedSnapshot) {
    try (final var contents = Files.newDirectoryStream(persistedSnapshot.getPath())) {
      var totalSize = 0L;
      var totalCount = 0L;
      for (final var path : contents) {
        if (Files.isRegularFile(path)) {
          final var size = Files.size(path);
          snapshotMetrics.observeSnapshotFileSize(size);
          totalSize += size;
          totalCount++;
        }
      }

      snapshotMetrics.observeSnapshotSize(totalSize);
      snapshotMetrics.observeSnapshotChunkCount(totalCount);
    } catch (final IOException e) {
      LOGGER.warn("Failed to observe size for snapshot {}", persistedSnapshot, e);
    }
  }

  private void purgePendingSnapshots(final long cutoffIndex) {
    LOGGER.debug(
        "Search for orphaned snapshots below oldest valid snapshot with index {} in {}",
        cutoffIndex,
        pendingDirectory);

    try (final var pendingSnapshots = Files.newDirectoryStream(pendingDirectory)) {
      for (final var pendingSnapshot : pendingSnapshots) {
        purgePendingSnapshot(cutoffIndex, pendingSnapshot);
      }
    } catch (final IOException e) {
      LOGGER.warn(
          "Failed to delete orphaned snapshots, could not list pending directory {}",
          pendingDirectory,
          e);
    }
  }

  private void purgePendingSnapshot(final long cutoffIndex, final Path pendingSnapshot) {
    final var optionalMetadata = FileBasedSnapshotMetadata.ofPath(pendingSnapshot);
    if (optionalMetadata.isPresent() && optionalMetadata.get().getIndex() < cutoffIndex) {
      try {
        FileUtil.deleteFolder(pendingSnapshot);
        LOGGER.debug("Deleted orphaned snapshot {}", pendingSnapshot);
      } catch (final IOException e) {
        LOGGER.warn(
            "Failed to delete orphaned snapshot {}, risk using unnecessary disk space",
            pendingSnapshot,
            e);
      }
    }
  }

  public Path getPath() {
    return snapshotsDirectory;
  }

  @Override
  public void close() {
    listeners.clear();
  }

  private boolean isCurrentSnapshotNewer(final FileBasedSnapshotMetadata metadata) {
    final var persistedSnapshot = currentPersistedSnapshotRef.get();
    return (persistedSnapshot != null && persistedSnapshot.getId().compareTo(metadata) >= 0);
  }

  PersistedSnapshot newSnapshot(final FileBasedSnapshotMetadata metadata, final Path directory) {
    final var currentPersistedSnapshot = currentPersistedSnapshotRef.get();

    if (isCurrentSnapshotNewer(metadata)) {
      LOGGER.debug("Snapshot is older then {} already exists", currentPersistedSnapshot);
      purgePendingSnapshots(metadata.getIndex() + 1);
      return currentPersistedSnapshot;
    }

    final var destination = buildSnapshotDirectory(metadata);
    try {
      tryAtomicDirectoryMove(directory, destination);
    } catch (final FileAlreadyExistsException e) {
      LOGGER.debug(
          "Expected to move snapshot from {} to {}, but it already exists",
          directory,
          destination,
          e);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    final var newPersistedSnapshot = new FileBasedSnapshot(destination, metadata);
    final var failed =
        !currentPersistedSnapshotRef.compareAndSet(currentPersistedSnapshot, newPersistedSnapshot);
    if (failed) {
      // we moved already the snapshot but we expected that this will be cleaned up by the next
      // successful snapshot
      final var errorMessage =
          "Expected that last snapshot is '%s', which should be replace with '%s', but last snapshot was '%s'.";
      throw new ConcurrentModificationException(
          String.format(
              errorMessage,
              currentPersistedSnapshot,
              newPersistedSnapshot,
              currentPersistedSnapshotRef.get()));
    }

    snapshotMetrics.incrementSnapshotCount();
    observeSnapshotSize(newPersistedSnapshot);

    LOGGER.debug("Purging snapshots older than {}", newPersistedSnapshot);
    if (currentPersistedSnapshot != null) {
      LOGGER.debug("Deleting snapshot {}", currentPersistedSnapshot);
      currentPersistedSnapshot.delete();
    }
    purgePendingSnapshots(newPersistedSnapshot.getIndex());

    listeners.forEach(listener -> listener.onNewSnapshot(newPersistedSnapshot));

    LOGGER.debug("Created new snapshot {}", newPersistedSnapshot);
    return newPersistedSnapshot;
  }

  private void purgePendingSnapshot(final Path pendingSnapshot) {
    try {
      FileUtil.deleteFolder(pendingSnapshot);
      LOGGER.debug("Deleted not completed (orphaned) snapshot {}", pendingSnapshot);
    } catch (final IOException e) {
      LOGGER.error("Failed to delete not completed (orphaned) snapshot {}", pendingSnapshot, e);
    }
  }

  private void tryAtomicDirectoryMove(final Path directory, final Path destination)
      throws IOException {
    try {
      Files.move(directory, destination, StandardCopyOption.ATOMIC_MOVE);
    } catch (final AtomicMoveNotSupportedException e) {
      Files.move(directory, destination);
    }
  }

  private Path buildPendingSnapshotDirectory(
      final long index, final long term, final WallClockTimestamp timestamp) {
    final var metadata = new FileBasedSnapshotMetadata(index, term, timestamp);
    return pendingDirectory.resolve(metadata.getSnapshotIdAsString());
  }

  private Path buildSnapshotDirectory(final FileBasedSnapshotMetadata metadata) {
    return snapshotsDirectory.resolve(metadata.getSnapshotIdAsString());
  }

  SnapshotMetrics getSnapshotMetrics() {
    return snapshotMetrics;
  }
}
