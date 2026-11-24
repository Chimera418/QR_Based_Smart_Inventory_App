package com.yourname.qrinventory.utils;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Parses scanned UPI QR payloads, builds NPCI-compliant UPI payment Intent URIs
 * (keeping literal '@' in pa= and adding a unique tr= reference), and parses UPI app responses.
 */
public final class PaymentQrParser {

    public static final String UPI_ID_PATTERN = "[a-zA-Z0-9._-]{2,}@[a-zA-Z0-9.-]{2,}";
    public static final String STORE_UPI_ID = "qrmart@upi";
    public static final String STORE_NAME = "QR Mart";

    private PaymentQrParser() {}

    public static final class ParsedQr {
        public final String raw;
        public final String upiId;
        public final String payeeName;
        public final String amount;
        public final boolean isUpi;

        public ParsedQr(String raw, String upiId, String payeeName, String amount) {
            this.raw = raw == null ? "" : raw;
            this.upiId = upiId;
            this.payeeName = payeeName;
            this.amount = amount;
            this.isUpi = upiId != null && !upiId.isEmpty();
        }

        public String displayLabel() {
            if (isUpi) {
                if (payeeName != null && !payeeName.isEmpty()) {
                    return payeeName + " (" + upiId + ")";
                }
                return upiId;
            }
            return raw;
        }
    }

    public enum UpiStatus { SUCCESS, FAILURE, SUBMITTED, UNKNOWN }

    public static final class UpiResponse {
        public final UpiStatus status;
        public final String txnId;
        public final String raw;

        public UpiResponse(UpiStatus status, String txnId, String raw) {
            this.status = status;
            this.txnId = txnId;
            this.raw = raw == null ? "" : raw;
        }
    }

    public static boolean isValidUpiId(String value) {
        return value != null && value.trim().matches(UPI_ID_PATTERN);
    }

    public static ParsedQr parse(String rawInput) {
        if (rawInput == null) return new ParsedQr("", null, null, null);
        String trimmed = rawInput.trim();
        if (trimmed.isEmpty()) return new ParsedQr("", null, null, null);

        // Plain UPI ID (e.g., merchant@okaxis)
        if (isValidUpiId(trimmed)) {
            return new ParsedQr(trimmed, trimmed, null, null);
        }

        // Standard NPCI UPI URI (upi://pay?pa=...&pn=...&am=...) or URL containing UPI query params
        int qIdx = trimmed.indexOf('?');
        if (qIdx >= 0 && qIdx + 1 < trimmed.length()) {
            String query = trimmed.substring(qIdx + 1);
            String pa = null;
            String pn = null;
            String am = null;

            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String key = decode(pair.substring(0, eq)).toLowerCase(Locale.ROOT);
                String val = decode(pair.substring(eq + 1)).trim();
                if ("pa".equals(key) && isValidUpiId(val)) {
                    pa = val;
                } else if ("pn".equals(key) && !val.isEmpty()) {
                    pn = val;
                } else if ("am".equals(key) && !val.isEmpty()) {
                    am = val;
                }
            }
            if (pa != null) {
                return new ParsedQr(trimmed, pa, pn, am);
            }
        }

        return new ParsedQr(trimmed, null, null, null);
    }

    /**
     * Builds an NPCI-compliant UPI Intent URI from a scanned QR:
     * - Keeps literal '@' in pa= (GPay/PhonePe reject '%40')
     * - For Merchant QRs (non-0000 mc), preserves merchant params (mc, orgid, mode, purpose, tr)
     *   while dropping sign/aid and ensuring a unique tr= reference
     * - For Personal (P2P) QRs, builds a clean minimal URI with only pa, pn, am, cu, tn
     *   (omitting tr/mc/aid/sign/mode so GPay does not trigger false bank-limit blocks)
     */
    public static String buildPayUriFromScan(ParsedQr parsed, double amount, String note) {
        if (parsed == null || !parsed.isUpi) return null;
        String raw = parsed.raw.trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        Map<String, String> rawParams = new LinkedHashMap<>();

        if (lower.startsWith("upi://")) {
            int qIdx = raw.indexOf('?');
            if (qIdx >= 0 && qIdx + 1 < raw.length()) {
                for (String pair : raw.substring(qIdx + 1).split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq <= 0) continue;
                    String key = decode(pair.substring(0, eq)).trim().toLowerCase(Locale.ROOT);
                    String val = decode(pair.substring(eq + 1)).trim();
                    if (!key.isEmpty() && !val.isEmpty()) {
                        rawParams.put(key, val);
                    }
                }
            }
        }

        boolean isMerchant = rawParams.containsKey("mc") && !"0000".equals(rawParams.get("mc"));
        String payee = rawParams.containsKey("pn")
                ? rawParams.get("pn")
                : ((parsed.payeeName != null && !parsed.payeeName.isEmpty()) ? parsed.payeeName : STORE_NAME);

        Map<String, String> params = new LinkedHashMap<>();
        if (isMerchant) {
            for (Map.Entry<String, String> entry : rawParams.entrySet()) {
                String key = entry.getKey();
                if ("sign".equals(key) || "aid".equals(key)) continue;
                params.put(key, entry.getValue());
            }
            params.put("pa", parsed.upiId);
            params.put("pn", payee);
            if (!params.containsKey("tr")) {
                params.put("tr", "QRM" + System.currentTimeMillis());
            }
        } else {
            params.put("pa", parsed.upiId);
            params.put("pn", payee);
        }

        params.put("am", String.format(Locale.US, "%.2f", Math.max(0.0, amount)));
        params.put("cu", "INR");
        if (note != null && !note.trim().isEmpty()) {
            params.put("tn", note.trim());
        }

        return assembleUpiUri(params);
    }

    public static String buildUpiUri(String upiId, String payeeName, double amount, String note) {
        String payee = (payeeName == null || payeeName.trim().isEmpty()) ? STORE_NAME : payeeName.trim();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pa", upiId == null ? "" : upiId.trim());
        params.put("pn", payee);
        params.put("am", String.format(Locale.US, "%.2f", Math.max(0.0, amount)));
        params.put("cu", "INR");
        if (note != null && !note.trim().isEmpty()) {
            params.put("tn", note.trim());
        }
        return assembleUpiUri(params);
    }

    private static String assembleUpiUri(Map<String, String> params) {
        StringBuilder sb = new StringBuilder("upi://pay?");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) sb.append('&');
            first = false;
            String key = e.getKey();
            // Keep '@' literal in pa= as required by GPay / PhonePe / NPCI UPI intent parsers
            String encodedVal = "pa".equals(key) ? encode(e.getValue()).replace("%40", "@") : encode(e.getValue());
            sb.append(encode(key)).append('=').append(encodedVal);
        }
        return sb.toString();
    }

    /**
     * Parses NPCI UPI Intent response strings returned by GPay / PhonePe / Paytm / Navi / BHIM.
     */
    public static UpiResponse parseUpiResponse(String response, String fallbackStatus) {
        String statusStr = null;
        String responseCode = null;
        String approvalRef = null;
        String txnId = null;
        String txnRef = null;

        if (response != null && !response.trim().isEmpty()) {
            for (String pair : response.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String key = decode(pair.substring(0, eq)).trim().toLowerCase(Locale.ROOT);
                String val = decode(pair.substring(eq + 1)).trim();
                if ("status".equals(key) || "txnstatus".equals(key)
                        || "paymentstatus".equals(key) || "result".equals(key)) {
                    if (!val.isEmpty()) statusStr = val;
                } else if ("responsecode".equals(key) && !val.isEmpty()) {
                    responseCode = val;
                } else if (("approvalrefno".equals(key) || "utr".equals(key) || "rrn".equals(key)) && !val.isEmpty()) {
                    approvalRef = val;
                } else if ("txnid".equals(key) && !val.isEmpty()) {
                    txnId = val;
                } else if ("txnref".equals(key) && !val.isEmpty()) {
                    txnRef = val;
                }
            }
        }
        if ((statusStr == null || statusStr.isEmpty()) && fallbackStatus != null) {
            statusStr = fallbackStatus.trim();
        }

        UpiStatus status = UpiStatus.UNKNOWN;
        if (statusStr != null && !statusStr.isEmpty()) {
            String upper = statusStr.toUpperCase(Locale.ROOT);
            if ("SUCCESS".equals(upper) || "COMPLETED".equals(upper) || "OK".equals(upper)) {
                status = UpiStatus.SUCCESS;
            } else if ("FAILURE".equals(upper) || "FAILED".equals(upper) || "DECLINED".equals(upper)) {
                status = UpiStatus.FAILURE;
            } else if ("SUBMITTED".equals(upper) || "PENDING".equals(upper)) {
                status = UpiStatus.SUBMITTED;
            }
        } else if ("00".equals(responseCode) || "0".equals(responseCode)) {
            status = UpiStatus.SUCCESS;
        } else if (response != null) {
            // Fallback for non-standard/JSON responses from third-party UPI apps
            String upperRaw = response.toUpperCase(Locale.ROOT);
            if (upperRaw.contains("FAILURE") || upperRaw.contains("FAILED")) {
                status = UpiStatus.FAILURE;
            } else if (upperRaw.contains("SUCCESS")) {
                status = UpiStatus.SUCCESS;
            }
        }

        String bestId = approvalRef != null ? approvalRef : (txnId != null ? txnId : txnRef);
        return new UpiResponse(status, bestId, response);
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return s;
        }
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s == null ? "" : s;
        }
    }
}
