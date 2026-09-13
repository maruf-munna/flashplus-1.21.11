package redsmods.flashplus.util;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Lightweight, zero-external-dependency OpenEXR writer.
 * Writes standard single-part scanline 16-bit half-float RGB OpenEXR files.
 * Fully compatible with Blender, DaVinci Resolve, Nuke, etc.
 */
public class ExrWriter {

    private static final int MAGIC = 0x01312f76; // 0x76, 0x2f, 0x31, 0x01
    private static final int VERSION_AND_FLAGS = 2; // Version 2, single-part scanline

    /**
     * Writes an RGB image to an OpenEXR file.
     *
     * @param file Output file
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @param linearRgb Linear float RGB array, size width * height * 3 (ordered [R0, G0, B0, R1, G1, B1, ...])
     * @throws IOException on I/O error
     */
    public static void writeRgbHalfExr(File file, int width, int height, float[] linearRgb) throws IOException {
        if (linearRgb.length < width * height * 3) {
            throw new IllegalArgumentException("linearRgb buffer too small: expected " + (width * height * 3) + ", got " + linearRgb.length);
        }

        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        // 1. Build header in memory
        ByteBuffer header = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(MAGIC);
        header.putInt(VERSION_AND_FLAGS);

        // Required standard attributes
        // channels (chlist): must be in alphabetical order: B, G, R
        writeAttributeHeader(header, "channels", "chlist", 18 * 3 + 1);
        writeChannel(header, "B");
        writeChannel(header, "G");
        writeChannel(header, "R");
        header.put((byte) 0); // End of chlist

        // compression: 0 = NO_COMPRESSION
        writeAttributeHeader(header, "compression", "compression", 1);
        header.put((byte) 0);

        // dataWindow: box2i (xMin, yMin, xMax, yMax)
        writeAttributeHeader(header, "dataWindow", "box2i", 16);
        header.putInt(0);
        header.putInt(0);
        header.putInt(width - 1);
        header.putInt(height - 1);

        // displayWindow: box2i (xMin, yMin, xMax, yMax)
        writeAttributeHeader(header, "displayWindow", "box2i", 16);
        header.putInt(0);
        header.putInt(0);
        header.putInt(width - 1);
        header.putInt(height - 1);

        // lineOrder: 0 = INCREASING_Y
        writeAttributeHeader(header, "lineOrder", "lineOrder", 1);
        header.put((byte) 0);

        // pixelAspectRatio: float = 1.0f
        writeAttributeHeader(header, "pixelAspectRatio", "float", 4);
        header.putFloat(1.0f);

        // screenWindowCenter: v2f = (0.0f, 0.0f)
        writeAttributeHeader(header, "screenWindowCenter", "v2f", 8);
        header.putFloat(0.0f);
        header.putFloat(0.0f);

        // screenWindowWidth: float = 1.0f
        writeAttributeHeader(header, "screenWindowWidth", "float", 4);
        header.putFloat(1.0f);

        // End of header
        header.put((byte) 0);
        header.flip();

        int headerSize = header.remaining();
        long tableSize = (long) height * 8L;
        long scanlineDataSize = (long) width * 2L * 3L; // B, G, R half channels
        long totalScanlineBlockSize = 8L + scanlineDataSize; // 4 bytes y + 4 bytes size + pixel data

        try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(file), 65536)) {
            // Write header
            byte[] headerBytes = new byte[headerSize];
            header.get(headerBytes);
            fos.write(headerBytes);

            // Write line offset table (8 bytes per scanline)
            ByteBuffer tableBuf = ByteBuffer.allocate(height * 8).order(ByteOrder.LITTLE_ENDIAN);
            long currentOffset = (long) headerSize + tableSize;
            for (int y = 0; y < height; y++) {
                tableBuf.putLong(currentOffset);
                currentOffset += totalScanlineBlockSize;
            }
            fos.write(tableBuf.array());

            // Write scanlines
            // Scanline format: int32 y, int32 pixelDataSize, B half array, G half array, R half array
            ByteBuffer scanlineBuf = ByteBuffer.allocate((int) totalScanlineBlockSize).order(ByteOrder.LITTLE_ENDIAN);

            for (int y = 0; y < height; y++) {
                scanlineBuf.clear();
                scanlineBuf.putInt(y);
                scanlineBuf.putInt((int) scanlineDataSize);

                int rowOffset = y * width * 3;

                // OpenEXR channel order: B, G, R
                // 1. Blue channel
                for (int x = 0; x < width; x++) {
                    float b = linearRgb[rowOffset + x * 3 + 2];
                    scanlineBuf.putShort(Float.floatToFloat16(b));
                }

                // 2. Green channel
                for (int x = 0; x < width; x++) {
                    float g = linearRgb[rowOffset + x * 3 + 1];
                    scanlineBuf.putShort(Float.floatToFloat16(g));
                }

                // 3. Red channel
                for (int x = 0; x < width; x++) {
                    float r = linearRgb[rowOffset + x * 3];
                    scanlineBuf.putShort(Float.floatToFloat16(r));
                }

                fos.write(scanlineBuf.array(), 0, (int) totalScanlineBlockSize);
            }
        }
    }

    private static void writeAttributeHeader(ByteBuffer buf, String name, String type, int size) {
        buf.put(name.getBytes(StandardCharsets.US_ASCII));
        buf.put((byte) 0);
        buf.put(type.getBytes(StandardCharsets.US_ASCII));
        buf.put((byte) 0);
        buf.putInt(size);
    }

    private static void writeChannel(ByteBuffer buf, String name) {
        buf.put(name.getBytes(StandardCharsets.US_ASCII));
        buf.put((byte) 0);
        buf.putInt(1); // 1 = HALF float (16-bit)
        buf.put((byte) 0); // pLinear = 0
        buf.put((byte) 0); // reserved
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.putInt(1); // xSampling = 1
        buf.putInt(1); // ySampling = 1
    }
}
