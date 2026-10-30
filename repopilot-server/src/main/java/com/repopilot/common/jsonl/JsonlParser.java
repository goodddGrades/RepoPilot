package com.repopilot.common.jsonl;

import com.repopilot.common.api.ErrorCode;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * JSONL 文件解析：负责"文件级"规则，不负责单行 JSON 的业务语义。
 *
 * <p>文件级规则（超出即 413，且调用方不得写入任何数据）：
 * <ul>
 *   <li>文件 ≤ 20 MiB；</li>
 *   <li>非空行 ≤ 10000；</li>
 *   <li>单行 ≤ 64 KiB。</li>
 * </ul>
 *
 * <p>解析规则：
 * <ul>
 *   <li>{@code \n} 与 {@code \r\n} 都算换行，行尾 {@code \r} 去掉；</li>
 *   <li>开头 UTF-8 BOM 丢弃（编辑器常见，不丢弃会让第一行 JSON 解析失败）；</li>
 *   <li>严格 UTF-8 解码：非法字节直接拒绝，不做替换字符兜底 ——
 *       用 {@code U+FFFD} 兜底会把"文件坏了"变成"内容长得怪"，更难排查；</li>
 *   <li>仅含空白的行跳过，但**占物理行号**。报错行号必须是用户能在编辑器里跳过去的行号，
 *       按"第几个非空行"报会让用户找不到。</li>
 * </ul>
 */
public final class JsonlParser {

    public static final int MAX_FILE_BYTES = 20 * 1024 * 1024;
    public static final int MAX_NON_BLANK_LINES = 10_000;
    public static final int MAX_LINE_BYTES = 64 * 1024;

    private JsonlParser() {
    }

    /**
     * 一行非空内容。
     *
     * @param lineNo 物理行号（从 1 开始，含被跳过的空行）
     * @param text   去掉行尾 {@code \r} 的原始文本
     */
    public record Line(int lineNo, String text) {
    }

    /**
     * 解析结果。
     *
     * @param lines            非空行，按出现顺序
     * @param physicalLineCount 物理行总数（末尾无换行也算一行）
     */
    public record Result(List<Line> lines, int physicalLineCount) {
        public int nonBlankCount() {
            return lines.size();
        }
    }

    public static Result parse(byte[] bytes) {
        if (bytes == null) {
            throw new JsonlParseException(ErrorCode.VALIDATION_ERROR, "file", "文件为空");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new JsonlParseException(ErrorCode.PAYLOAD_TOO_LARGE, "file",
                    "文件超过 " + (MAX_FILE_BYTES / 1024 / 1024) + " MiB 上限");
        }

        byte[] body = stripBom(bytes);
        String text = decodeStrict(body);

        List<Line> lines = new ArrayList<>();
        int physical = 0;
        int start = 0;
        int length = text.length();
        while (start <= length) {
            int nl = text.indexOf('\n', start);
            int end = nl < 0 ? length : nl;
            String raw = text.substring(start, end);
            if (!raw.isEmpty() && raw.charAt(raw.length() - 1) == '\r') {
                raw = raw.substring(0, raw.length() - 1);
            }
            physical++;
            if (!isBlank(raw)) {
                int byteLength = raw.getBytes(StandardCharsets.UTF_8).length;
                if (byteLength > MAX_LINE_BYTES) {
                    throw new JsonlParseException(ErrorCode.PAYLOAD_TOO_LARGE, "file",
                            "LINE_TOO_LONG 第 " + physical + " 行超过 " + (MAX_LINE_BYTES / 1024) + " KiB");
                }
                lines.add(new Line(physical, raw));
                if (lines.size() > MAX_NON_BLANK_LINES) {
                    throw new JsonlParseException(ErrorCode.PAYLOAD_TOO_LARGE, "file",
                            "文件非空行超过 " + MAX_NON_BLANK_LINES + " 行上限");
                }
            }
            if (nl < 0) {
                break;
            }
            start = nl + 1;
        }
        return new Result(List.copyOf(lines), physical);
    }

    private static byte[] stripBom(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            byte[] out = new byte[bytes.length - 3];
            System.arraycopy(bytes, 3, out, 0, out.length);
            return out;
        }
        return bytes;
    }

    private static String decodeStrict(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer buffer = decoder.decode(ByteBuffer.wrap(bytes));
            return buffer.toString();
        } catch (CharacterCodingException ex) {
            throw new JsonlParseException(ErrorCode.VALIDATION_ERROR, "file",
                    "文件不是合法的 UTF-8 编码");
        }
    }

    private static boolean isBlank(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
