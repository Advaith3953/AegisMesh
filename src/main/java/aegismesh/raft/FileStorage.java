package aegismesh.raft;

import aegismesh.raft.Types.PersistentState;
import aegismesh.raft.Types.Storage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Crash-safe JSON file: write a temp file, fsync it, atomically rename it over the real file, then fsync the
 * directory so the rename itself survives a power loss. A missing file loads as empty state; a corrupt file is an
 * error (never silently treated as empty, which would let a node forget its vote).
 */
public final class FileStorage implements Storage {
    private final Path path;

    public FileStorage(Path path) {
        this.path = path;
    }

    @Override
    public PersistentState load() throws IOException {
        byte[] b;
        try {
            b = Files.readAllBytes(path);
        } catch (NoSuchFileException e) {
            return PersistentState.empty();
        }
        try {
            return Codec.state(new String(b, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException("corrupt raft state file " + path + ": " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void save(PersistentState s) throws IOException {
        Path dir = path.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(path.getFileName() + ".tmp");
        byte[] data = Codec.encode(s).getBytes(StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buf = ByteBuffer.wrap(data);
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.force(true);
        }
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        try (FileChannel d = FileChannel.open(dir, StandardOpenOption.READ)) {
            d.force(true);
        } catch (IOException e) {
            // some filesystems refuse fsync on a directory; that is not a write failure
        }
    }
}
