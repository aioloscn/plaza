package com.aiolos.plaza.order.workflow.payment;

import com.aiolos.plaza.order.config.AlipayConfig;
import com.alibaba.fastjson.JSONObject;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradeFastpayRefundQueryRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeFastpayRefundQueryResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 支付网关能力收口，避免支付查询、退款和验签散落在 facade 与任务服务里
 */
@Component
public class AlipayGatewaySupport {

    private final AlipayConfig alipayConfig;

    public AlipayGatewaySupport(AlipayConfig alipayConfig) {
        this.alipayConfig = alipayConfig;
    }

    public boolean verifySignature(Map<String, String> params) {
        try {
            // 支付回调与退款回调都统一走这里验签，避免控制层各自处理导致口径不一致
            return AlipaySignature.rsaCheckV1(
                    params,
                    alipayConfig.getAlipayPublicKey(),
                    alipayConfig.getCharset(),
                    alipayConfig.getSignType());
        } catch (Exception e) {
            return false;
        }
    }

    public TradeQueryResult queryTrade(String outTradeNo, String tradeNo) throws Exception {
        // 查单时优先带上商户单号，若本地已拿到第三方流水号则一并传递，减少三方侧模糊匹配
        AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
        JSONObject bizContent = new JSONObject();
        bizContent.put("out_trade_no", outTradeNo);
        if (StringUtils.hasText(tradeNo)) {
            bizContent.put("trade_no", tradeNo);
        }
        request.setBizContent(bizContent.toJSONString());
        AlipayTradeQueryResponse response = buildClient().execute(request);
        if (response == null) {
            return new TradeQueryResult("UNKNOWN", tradeNo, null, null, "响应为空");
        }
        // 支付侧金额是字符串，转换为 BigDecimal 后再交给上游做精确比对
        String buyerId = response.getBuyerUserId();
        BigDecimal totalAmount = null;
        if (StringUtils.hasText(response.getTotalAmount())) {
            totalAmount = new BigDecimal(response.getTotalAmount());
        }
        return new TradeQueryResult(
                normalizeTradeStatus(response.getTradeStatus(), response.isSuccess()),
                StringUtils.hasText(response.getTradeNo()) ? response.getTradeNo() : tradeNo,
                buyerId,
                totalAmount,
                response.getSubMsg()
        );
    }

    public RefundExecuteResult executeRefund(String outTradeNo,
                                             String tradeNo,
                                             String refundRequestNo,
                                             BigDecimal refundAmount,
                                             String refundReason) throws Exception {
        // 所有退款都强制携带 out_request_no，后续重复调用与退款查询都依赖这个业务唯一号做幂等
        AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
        JSONObject bizContent = new JSONObject();
        bizContent.put("out_trade_no", outTradeNo);
        if (StringUtils.hasText(tradeNo)) {
            bizContent.put("trade_no", tradeNo);
        }
        bizContent.put("refund_amount", refundAmount.toPlainString());
        bizContent.put("refund_reason", refundReason);
        bizContent.put("out_request_no", refundRequestNo);
        request.setBizContent(bizContent.toJSONString());
        AlipayTradeRefundResponse response = buildClient().execute(request);
        if (response == null) {
            return new RefundExecuteResult("UNKNOWN", null, null, false, "支付宝退款响应为空");
        }
        // 支付宝退款受理成功不等于资金一定已经变更，fundChange=Y 才视为本次同步退款成功
        String status;
        if (response.isSuccess() && "Y".equalsIgnoreCase(response.getFundChange())) {
            status = "SUCCESS";
        } else if (response.isSuccess()) {
            status = "PROCESSING";
        } else {
            status = "FAILED";
        }
        return new RefundExecuteResult(
                status,
                request.getBizContent(),
                response.getBody(),
                response.isSuccess(),
                StringUtils.hasText(response.getSubMsg()) ? response.getSubMsg() : response.getMsg()
        );
    }

    public RefundQueryResult queryRefund(String outTradeNo,
                                         String tradeNo,
                                         String refundRequestNo) throws Exception {
        // 退款查询与退款执行保持同一组定位参数，避免因为只传 tradeNo 或只传 requestNo 导致对账歧义
        AlipayTradeFastpayRefundQueryRequest request = new AlipayTradeFastpayRefundQueryRequest();
        JSONObject bizContent = new JSONObject();
        bizContent.put("out_trade_no", outTradeNo);
        if (StringUtils.hasText(tradeNo)) {
            bizContent.put("trade_no", tradeNo);
        }
        bizContent.put("out_request_no", refundRequestNo);
        request.setBizContent(bizContent.toJSONString());
        AlipayTradeFastpayRefundQueryResponse response = buildClient().execute(request);
        if (response == null) {
            return new RefundQueryResult("UNKNOWN", request.getBizContent(), null, "支付宝退款查询响应为空");
        }
        // 查询结果统一折叠到补偿域状态，任务层只消费稳定语义
        return new RefundQueryResult(
                normalizeRefundStatus(response.getRefundStatus(), response.isSuccess()),
                request.getBizContent(),
                response.getBody(),
                StringUtils.hasText(response.getSubMsg()) ? response.getSubMsg() : response.getMsg()
        );
    }

    public AlipayClient buildClient() {
        // 每次按配置构建客户端，避免在业务层散落网关参数拼装逻辑
        return new DefaultAlipayClient(
                alipayConfig.getGatewayUrl(),
                alipayConfig.getAppId(),
                alipayConfig.getMerchantPrivateKey(),
                alipayConfig.getFormat(),
                alipayConfig.getCharset(),
                alipayConfig.getAlipayPublicKey(),
                alipayConfig.getSignType());
    }

    private String normalizeTradeStatus(String tradeStatus, boolean success) {
        // 任务层只关心少数稳定语义，这里把三方原始状态折叠成补偿域可消费的状态枚举
        if (!success) {
            return "UNKNOWN";
        }
        if ("TRADE_SUCCESS".equalsIgnoreCase(tradeStatus) || "TRADE_FINISHED".equalsIgnoreCase(tradeStatus)) {
            return "PAID";
        }
        if ("WAIT_BUYER_PAY".equalsIgnoreCase(tradeStatus)) {
            return "UNPAID";
        }
        if ("TRADE_CLOSED".equalsIgnoreCase(tradeStatus)) {
            return "CLOSED";
        }
        return StringUtils.hasText(tradeStatus) ? tradeStatus : "UNKNOWN";
    }

    private String normalizeRefundStatus(String refundStatus, boolean success) {
        // 退款对账同样只保留 SUCCESS / PROCESSING / FAILED / UNKNOWN 四类结果
        if (!success) {
            return "UNKNOWN";
        }
        if ("REFUND_SUCCESS".equalsIgnoreCase(refundStatus)) {
            return "SUCCESS";
        }
        if ("REFUND_PROCESSING".equalsIgnoreCase(refundStatus)) {
            return "PROCESSING";
        }
        if ("REFUND_FAIL".equalsIgnoreCase(refundStatus)) {
            return "FAILED";
        }
        return StringUtils.hasText(refundStatus) ? refundStatus : "UNKNOWN";
    }

    public record TradeQueryResult(String tradeStatus,
                                   String tradeNo,
                                   String buyerId,
                                   BigDecimal totalAmount,
                                   String message) {
        // tradeStatus 使用补偿域语义值，如 PAID / UNPAID / CLOSED / UNKNOWN
    }

    public record RefundExecuteResult(String refundStatus,
                                      String requestPayload,
                                      String responsePayload,
                                      boolean accepted,
                                      String message) {
        // accepted 表示网关是否受理请求，不等同于最终退款结果
    }

    public record RefundQueryResult(String refundStatus,
                                    String requestPayload,
                                    String responsePayload,
                                    String message) {
        // refundStatus 使用补偿域语义值，如 SUCCESS / PROCESSING / FAILED / UNKNOWN
    }
}
