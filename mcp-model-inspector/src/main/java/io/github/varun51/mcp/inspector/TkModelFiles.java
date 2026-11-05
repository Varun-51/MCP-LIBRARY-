package io.github.varun51.mcp.inspector;

import static io.github.varun51.mcp.core.TkTool.mtkResult;

import io.github.varun51.mcp.core.TkToolException;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class TkModelFiles {

    static final long _TKMAX_HEADER_BYTES = 100L * 1024 * 1024;
    static final int _TKMAX_TENSORS = 1_000_000;
    static final int _TKMAX_LISTED_TENSORS = 50;
    public static final Map<String, Integer> _TKSAFETENSORS_BYTES = Map.ofEntries(
            Map.entry("F64", 8), Map.entry("F32", 4), Map.entry("F16", 2), Map.entry("BF16", 2), Map.entry("F8_E4M3", 1), Map.entry("F8_E5M2", 1),
            Map.entry("I64", 8), Map.entry("I32", 4), Map.entry("I16", 2), Map.entry("I8", 1), Map.entry("U8", 1), Map.entry("BOOL", 1));
    private static final String[] _TKGGML_TYPES = {"F32", "F16", "Q4_0", "Q4_1", "Q4_2", "Q4_3", "Q5_0", "Q5_1", "Q8_0", "Q8_1", "Q2_K", "Q3_K", "Q4_K",
            "Q5_K", "Q6_K", "Q8_K", "IQ2_XXS", "IQ2_XS", "IQ3_XXS", "IQ1_S", "IQ4_NL", "IQ3_S", "IQ2_S", "IQ4_XS", "I8", "I16", "I32", "I64", "F64", "IQ1_M", "BF16"};

    private TkModelFiles() {
    }

    public static Map<String, Object> mtkInspect(Path tkFile) throws IOException {
        try (InputStream tkIn = Files.newInputStream(tkFile)) {
            return mtkInspect(tkIn, Files.size(tkFile));
        }
    }

    public static Map<String, Object> mtkInspect(InputStream tkSource, long tkFileSize) throws IOException {
        InputStream tkIn = new BufferedInputStream(tkSource);
        byte[] tkMagic = tkIn.readNBytes(4);
        if (new String(tkMagic, StandardCharsets.US_ASCII).equals("GGUF")) {
            return mtkGguf(new DataInputStream(tkIn));
        }
        return mtkSafetensors(tkMagic, tkIn, tkFileSize);
    }

    private static Map<String, Object> mtkSafetensors(byte[] tkPrefix, InputStream tkIn, long tkFileSize) throws IOException {
        byte[] tkLengthBytes = new byte[8];
        System.arraycopy(tkPrefix, 0, tkLengthBytes, 0, tkPrefix.length);
        TkToolException.mtkCheck(tkPrefix.length == 4 && tkIn.readNBytes(tkLengthBytes, 4, 4) == 4, "file is neither safetensors nor GGUF");
        long tkHeaderLength = ByteBuffer.wrap(tkLengthBytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
        TkToolException.mtkCheck(tkHeaderLength > 0 && tkHeaderLength <= _TKMAX_HEADER_BYTES && 8 + tkHeaderLength <= tkFileSize, "file is neither safetensors nor GGUF");
        Map<String, Object> tkHeader;
        try {
            tkHeader = McpJsonDefaults.getMapper().readValue(tkIn.readNBytes((int) tkHeaderLength), new TypeRef<>() {
            });
        } catch (IOException tkError) {
            throw new TkToolException("safetensors header is not valid JSON");
        }
        long tkDataBytes = tkFileSize - 8 - tkHeaderLength;
        Map<String, Long> tkByDtype = new TreeMap<>();
        List<Map<String, Object>> tkListed = new ArrayList<>();
        long tkParameters = 0;
        for (Map.Entry<String, Object> tkEntry : tkHeader.entrySet()) {
            if (tkEntry.getKey().equals("__metadata__")) {
                continue;
            }
            Map<?, ?> tkTensor = (Map<?, ?>) tkEntry.getValue();
            String tkDtype = String.valueOf(tkTensor.get("dtype"));
            Integer tkWidth = _TKSAFETENSORS_BYTES.get(tkDtype);
            TkToolException.mtkCheck(tkWidth != null, "unsupported dtype " + tkDtype + " in tensor " + tkEntry.getKey());
            long tkCount = mtkProduct((List<?>) tkTensor.get("shape"));
            List<?> tkOffsets = (List<?>) tkTensor.get("data_offsets");
            long tkBegin = ((Number) tkOffsets.get(0)).longValue();
            long tkEnd = ((Number) tkOffsets.get(1)).longValue();
            TkToolException.mtkCheck(tkBegin >= 0 && tkEnd <= tkDataBytes && tkEnd - tkBegin == tkCount * tkWidth, "tensor " + tkEntry.getKey() + " has inconsistent data offsets");
            tkParameters += tkCount;
            tkByDtype.merge(tkDtype, tkCount, Long::sum);
            if (tkListed.size() < _TKMAX_LISTED_TENSORS) {
                tkListed.add(mtkResult("name", tkEntry.getKey(), "dtype", tkDtype, "shape", tkTensor.get("shape")));
            }
        }
        return mtkResult("format", "safetensors", "tensors", tkHeader.size() - (tkHeader.containsKey("__metadata__") ? 1 : 0), "parameters", tkParameters,
                "parameters_by_dtype", tkByDtype, "metadata", tkHeader.getOrDefault("__metadata__", Map.of()), "tensor_sample", tkListed);
    }

    private static Map<String, Object> mtkGguf(DataInputStream tkIn) throws IOException {
        try {
            int tkVersion = Integer.reverseBytes(tkIn.readInt());
            long tkTensorCount = Long.reverseBytes(tkIn.readLong());
            long tkMetadataCount = Long.reverseBytes(tkIn.readLong());
            TkToolException.mtkCheck(tkVersion >= 2 && tkTensorCount >= 0 && tkTensorCount <= _TKMAX_TENSORS && tkMetadataCount >= 0 && tkMetadataCount <= 100_000, "invalid GGUF header");
            Map<String, Object> tkMetadata = new TreeMap<>();
            for (long tkIndex = 0; tkIndex < tkMetadataCount; tkIndex++) {
                String tkKey = mtkGgufString(tkIn);
                tkMetadata.put(tkKey, mtkGgufValue(tkIn, Integer.reverseBytes(tkIn.readInt())));
            }
            Map<String, Long> tkByType = new TreeMap<>();
            List<Map<String, Object>> tkListed = new ArrayList<>();
            long tkParameters = 0;
            for (long tkIndex = 0; tkIndex < tkTensorCount; tkIndex++) {
                String tkName = mtkGgufString(tkIn);
                int tkDimensions = Integer.reverseBytes(tkIn.readInt());
                TkToolException.mtkCheck(tkDimensions >= 1 && tkDimensions <= 8, "invalid tensor rank in " + tkName);
                List<Long> tkShape = new ArrayList<>();
                for (int tkDimension = 0; tkDimension < tkDimensions; tkDimension++) {
                    tkShape.add(Long.reverseBytes(tkIn.readLong()));
                }
                int tkType = Integer.reverseBytes(tkIn.readInt());
                tkIn.readLong();
                String tkTypeName = tkType >= 0 && tkType < _TKGGML_TYPES.length ? _TKGGML_TYPES[tkType] : "TYPE_" + tkType;
                long tkCount = mtkProduct(tkShape);
                tkParameters += tkCount;
                tkByType.merge(tkTypeName, tkCount, Long::sum);
                if (tkListed.size() < _TKMAX_LISTED_TENSORS) {
                    tkListed.add(mtkResult("name", tkName, "type", tkTypeName, "shape", tkShape));
                }
            }
            return mtkResult("format", "gguf", "version", tkVersion, "tensors", tkTensorCount, "parameters", tkParameters,
                    "parameters_by_type", tkByType, "architecture", tkMetadata.getOrDefault("general.architecture", "unknown"), "metadata", tkMetadata, "tensor_sample", tkListed);
        } catch (EOFException tkTruncated) {
            throw new TkToolException("GGUF file is truncated");
        }
    }

    private static Object mtkGgufValue(DataInputStream tkIn, int tkType) throws IOException {
        return switch (tkType) {
            case 0 -> tkIn.readUnsignedByte();
            case 1 -> (int) tkIn.readByte();
            case 2 -> Short.toUnsignedInt(Short.reverseBytes(tkIn.readShort()));
            case 3 -> (int) Short.reverseBytes(tkIn.readShort());
            case 4 -> Integer.toUnsignedLong(Integer.reverseBytes(tkIn.readInt()));
            case 5 -> Integer.reverseBytes(tkIn.readInt());
            case 6 -> Float.intBitsToFloat(Integer.reverseBytes(tkIn.readInt()));
            case 7 -> tkIn.readByte() != 0;
            case 8 -> mtkGgufString(tkIn);
            case 9 -> mtkGgufArray(tkIn);
            case 10, 11 -> Long.reverseBytes(tkIn.readLong());
            case 12 -> Double.longBitsToDouble(Long.reverseBytes(tkIn.readLong()));
            default -> throw new TkToolException("unknown GGUF metadata type " + tkType);
        };
    }

    private static Object mtkGgufArray(DataInputStream tkIn) throws IOException {
        int tkElementType = Integer.reverseBytes(tkIn.readInt());
        long tkLength = Long.reverseBytes(tkIn.readLong());
        TkToolException.mtkCheck(tkLength >= 0 && tkLength <= 10_000_000, "GGUF array too large");
        for (long tkIndex = 0; tkIndex < tkLength; tkIndex++) {
            mtkGgufValue(tkIn, tkElementType);
        }
        return mtkResult("array_length", tkLength);
    }

    private static String mtkGgufString(DataInputStream tkIn) throws IOException {
        long tkLength = Long.reverseBytes(tkIn.readLong());
        TkToolException.mtkCheck(tkLength >= 0 && tkLength <= 1_048_576, "GGUF string too long");
        byte[] tkBytes = new byte[(int) tkLength];
        tkIn.readFully(tkBytes);
        return new String(tkBytes, StandardCharsets.UTF_8);
    }

    private static long mtkProduct(List<?> tkShape) {
        long tkProduct = 1;
        for (Object tkDimension : tkShape) {
            long tkValue = ((Number) tkDimension).longValue();
            TkToolException.mtkCheck(tkValue >= 0, "negative tensor dimension");
            tkProduct = Math.multiplyExact(tkProduct, tkValue);
        }
        return tkProduct;
    }
}
