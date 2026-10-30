package com.repopilot.common.cursor;

import com.repopilot.common.api.ErrorCode;
import com.repopilot.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 分页游标的签名与校验。
 *
 * <p>游标是**客户端可读但不许改**的不透明串：内容用 base64url 编码，末尾附 HMAC-SHA256 截断标签。
 * 只做 base64 不做签名的话，调用方改一个字段就能跳读到别人的数据范围；只做加密不做签名，
 * 又无法区分"合法但过期"与"被篡改"。
 *
 * <p>密钥来源 {@code repopilot.trace.cursor-secret}。未配置时生成一次性随机密钥：
 * <b>宁可重启后旧游标失效（返回 400，前端从第一页重查），也不写死一个仓库里的默认密钥</b>——
 * 后者会让所有部署共用同一个"秘密"。
 */
@Component
public class CursorSigner {

    private static final Logger log = LoggerFactory.getLogger(CursorSigner.class);

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MAC_BYTES = 16;
    private static final String INVALID = "游标无效或已被修改，请从第一页重新查询";

    private final byte[] secret;
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder decoder = Base64.getUrlDecoder();

    public CursorSigner(@Value("${repopilot.trace.cursor-secret:}") String configured) {
        if (configured == null || configured.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.secret = random;
            log.warn("未配置 repopilot.trace.cursor-secret：本进程使用随机签名密钥，"
                    + "重启后既有游标将返回 400（前端应回到第一页）");
        } else {
            this.secret = configured.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * 签名。
     *
     * @param payload 由调用方保证无歧义的规范化字符串（字段之间用 {@code |} 分隔且字段值不含该字符）
     */
    public String sign(String payload) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        byte[] mac = hmac(body);
        return encoder.encodeToString(body) + "." + encoder.encodeToString(mac);
    }

    /**
     * 校验并取出原文。
     *
     * <p>任何解析/签名失败都收敛为同一个 400：区分"签名错"和"格式错"只会帮攻击者试探。
     */
    public String verify(String token) {
        if (token == null || token.isBlank()) {
            throw invalid();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1 || token.indexOf('.', dot + 1) >= 0) {
            throw invalid();
        }
        byte[] body;
        byte[] mac;
        try {
            body = decoder.decode(token.substring(0, dot));
            mac = decoder.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
        if (!MessageDigest.isEqual(hmac(body), mac)) {
            throw invalid();
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private byte[] hmac(byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            byte[] full = mac.doFinal(body);
            byte[] truncated = new byte[MAC_BYTES];
            System.arraycopy(full, 0, truncated, 0, MAC_BYTES);
            return truncated;
        } catch (Exception ex) {
            // 算法/密钥问题属于部署错误，不能当成"游标不合法"返回 400
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "游标签名组件不可用");
        }
    }

    private static BusinessException invalid() {
        return BusinessException.field("cursor", INVALID);
    }
}
