package com.repopilot.common.jsonl;

import com.repopilot.common.api.ErrorCode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 文件级规则：这些决定"整批能不能开始"，一旦放过就会把脏数据带进库。 */
class JsonlParserTest {

    @Test
    void bomShouldBeStripped() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, bytes, 0, bom.length);
        System.arraycopy(body, 0, bytes, bom.length, body.length);

        JsonlParser.Result result = JsonlParser.parse(bytes);
        assertThat(result.lines()).hasSize(1);
        assertThat(result.lines().getFirst().text()).isEqualTo("{\"a\":1}");
        assertThat(result.lines().getFirst().lineNo()).isEqualTo(1);
    }

    @Test
    void crlfShouldBeHandledWithoutTrailingCarriageReturn() {
        JsonlParser.Result result = JsonlParser.parse("{\"a\":1}\r\n{\"b\":2}\r\n".getBytes(StandardCharsets.UTF_8));
        assertThat(result.lines()).hasSize(2);
        assertThat(result.lines().get(0).text()).isEqualTo("{\"a\":1}");
        assertThat(result.lines().get(1).text()).isEqualTo("{\"b\":2}");
    }

    @Test
    void blankLinesShouldBeSkippedButKeepPhysicalLineNumbers() {
        // 第 2 行是空行、第 3 行只有空格：都要占物理行号
        String input = "{\"a\":1}\n\n   \n{\"b\":2}\n";
        JsonlParser.Result result = JsonlParser.parse(input.getBytes(StandardCharsets.UTF_8));

        assertThat(result.lines()).hasSize(2);
        assertThat(result.lines().get(0).lineNo()).isEqualTo(1);
        assertThat(result.lines().get(1).lineNo()).isEqualTo(4);
        assertThat(result.physicalLineCount()).isEqualTo(5);
    }

    @Test
    void lastLineWithoutTrailingNewlineShouldStillBeCounted() {
        JsonlParser.Result result = JsonlParser.parse("{\"a\":1}\n{\"b\":2}".getBytes(StandardCharsets.UTF_8));
        assertThat(result.lines()).hasSize(2);
        assertThat(result.lines().get(1).lineNo()).isEqualTo(2);
    }

    @Test
    void lineLongerThanLimitShouldBeRejectedAsPayloadTooLarge() {
        String longLine = "{\"a\":\"" + "x".repeat(JsonlParser.MAX_LINE_BYTES) + "\"}";
        assertThatThrownBy(() -> JsonlParser.parse(longLine.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(JsonlParseException.class)
                .satisfies(ex -> assertThat(((JsonlParseException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE))
                .hasMessageContaining("LINE_TOO_LONG");
    }

    @Test
    void invalidUtf8ShouldBeRejectedInsteadOfReplaced() {
        byte[] bytes = {'{', (byte) 0xFF, (byte) 0xFE, '}'};
        assertThatThrownBy(() -> JsonlParser.parse(bytes))
                .isInstanceOf(JsonlParseException.class)
                .hasMessageContaining("UTF-8");
    }

    @Test
    void tooManyNonBlankLinesShouldBeRejected() {
        String line = "{\"a\":1}\n";
        String input = line.repeat(JsonlParser.MAX_NON_BLANK_LINES + 1);
        assertThatThrownBy(() -> JsonlParser.parse(input.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(JsonlParseException.class)
                .satisfies(ex -> assertThat(((JsonlParseException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE))
                .hasMessageContaining("10000");
    }

    @Test
    void fileLargerThanLimitShouldBeRejectedBeforeDecoding() {
        byte[] bytes = new byte[JsonlParser.MAX_FILE_BYTES + 1];
        assertThatThrownBy(() -> JsonlParser.parse(bytes))
                .isInstanceOf(JsonlParseException.class)
                .satisfies(ex -> assertThat(((JsonlParseException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
    }

    @Test
    void emptyFileShouldYieldNoLines() {
        JsonlParser.Result result = JsonlParser.parse("\n\n".getBytes(StandardCharsets.UTF_8));
        assertThat(result.lines()).isEmpty();
        assertThat(result.physicalLineCount()).isEqualTo(3);
    }
}
