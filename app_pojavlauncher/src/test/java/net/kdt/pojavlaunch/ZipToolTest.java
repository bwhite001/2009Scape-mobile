package net.kdt.pojavlaunch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Test;

/**
 * Exercises the zip-bomb caps added to {@link Tools.ZipTool#unzip} (plan 015): a total
 * uncompressed-bytes cap, a per-entry uncompressed-bytes cap, and an entry-count cap.
 * Plain JUnit4, no Android/Robolectric dependency, following
 * customcontrols/CameraPanTest.java's pattern.
 */
public class ZipToolTest {

    private static long readLongCap(String fieldName) throws Exception {
        Field f = Tools.ZipTool.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.getLong(null);
    }

    private static int readIntCap(String fieldName) throws Exception {
        Field f = Tools.ZipTool.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.getInt(null);
    }

    /** Writes a zip containing {@code name -> contents} entries and returns its path. */
    private static File buildZip(File dir, String zipName, String[] names, byte[][] contents) throws IOException {
        File zipFile = new File(dir, zipName);
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            for (int i = 0; i < names.length; i++) {
                zos.putNextEntry(new ZipEntry(names[i]));
                zos.write(contents[i]);
                zos.closeEntry();
            }
        }
        return zipFile;
    }

    @Test
    public void normalSmallZipExtractsSuccessfully() throws Exception {
        File srcDir = Files.createTempDirectory("ziptool-src").toFile();
        File outDir = Files.createTempDirectory("ziptool-out").toFile();

        byte[] a = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] b = "world, this is a small test entry".getBytes(StandardCharsets.UTF_8);
        byte[] c = "third entry".getBytes(StandardCharsets.UTF_8);
        File zip = buildZip(srcDir, "normal.zip",
                new String[]{"a.txt", "sub/b.txt", "c.txt"},
                new byte[][]{a, b, c});

        Tools.ZipTool.unzip(zip, outDir);

        assertArrayEquals(a, Files.readAllBytes(new File(outDir, "a.txt").toPath()));
        assertArrayEquals(b, Files.readAllBytes(new File(outDir, "sub/b.txt").toPath()));
        assertArrayEquals(c, Files.readAllBytes(new File(outDir, "c.txt").toPath()));
    }

    @Test
    public void entryCountOverCapIsRejected() throws Exception {
        int maxEntryCount = readIntCap("MAX_ENTRY_COUNT");

        File srcDir = Files.createTempDirectory("ziptool-src").toFile();
        File outDir = Files.createTempDirectory("ziptool-out").toFile();

        int entryCount = maxEntryCount + 1;
        File zip = new File(srcDir, "bomb-entries.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            for (int i = 0; i < entryCount; i++) {
                zos.putNextEntry(new ZipEntry("e" + i));
                zos.closeEntry();
            }
        }

        IOException thrown = assertThrows(IOException.class, () -> Tools.ZipTool.unzip(zip, outDir));
        assertTrue(thrown.getMessage(), thrown.getMessage().contains("too many entries"));
    }

    @Test
    public void oversizedEntryIsRejected() throws Exception {
        long maxEntryBytes = readLongCap("MAX_ENTRY_UNCOMPRESSED_BYTES");

        File srcDir = Files.createTempDirectory("ziptool-src").toFile();
        File outDir = Files.createTempDirectory("ziptool-out").toFile();

        // All-zero payload compresses to almost nothing, so the zip fixture on disk stays
        // tiny even though it decompresses to just over the per-entry cap.
        long payloadSize = maxEntryBytes + 1024;
        File zip = new File(srcDir, "bomb-size.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("huge.bin"));
            byte[] chunk = new byte[1024 * 1024];
            long written = 0;
            while (written < payloadSize) {
                int toWrite = (int) Math.min(chunk.length, payloadSize - written);
                zos.write(chunk, 0, toWrite);
                written += toWrite;
            }
            zos.closeEntry();
        }

        IOException thrown = assertThrows(IOException.class, () -> Tools.ZipTool.unzip(zip, outDir));
        assertTrue(thrown.getMessage(), thrown.getMessage().contains("max uncompressed size"));
        // The partially-written file must be cleaned up, not left half-extracted on disk.
        assertEquals(false, new File(outDir, "huge.bin").exists());
    }
}
