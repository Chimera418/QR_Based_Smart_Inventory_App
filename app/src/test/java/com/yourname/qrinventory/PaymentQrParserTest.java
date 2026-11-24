package com.yourname.qrinventory;

import com.yourname.qrinventory.utils.CartReservationManager;
import com.yourname.qrinventory.utils.PaymentQrParser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PaymentQrParserTest {

    @Test
    public void parsesStandardUpiUri() {
        String qr = "upi://pay?pa=merchant@okaxis&pn=QR%20Mart%20Store&am=245.50&cu=INR";
        PaymentQrParser.ParsedQr result = PaymentQrParser.parse(qr);

        assertTrue(result.isUpi);
        assertEquals("merchant@okaxis", result.upiId);
        assertEquals("QR Mart Store", result.payeeName);
        assertEquals("245.50", result.amount);
        assertEquals("QR Mart Store (merchant@okaxis)", result.displayLabel());
    }

    @Test
    public void parsesPlainUpiId() {
        PaymentQrParser.ParsedQr result = PaymentQrParser.parse("  shopper.1@okhdfcbank  ");

        assertTrue(result.isUpi);
        assertEquals("shopper.1@okhdfcbank", result.upiId);
        assertNull(result.payeeName);
        assertEquals("shopper.1@okhdfcbank", result.displayLabel());
    }

    @Test
    public void handlesNonUpiQrGracefully() {
        PaymentQrParser.ParsedQr result = PaymentQrParser.parse("STORE-COUNTER-01");

        assertFalse(result.isUpi);
        assertNull(result.upiId);
        assertEquals("STORE-COUNTER-01", result.raw);
        assertEquals("STORE-COUNTER-01", result.displayLabel());
    }

    @Test
    public void buildsPayUriPreservingMerchantParamsAndInjectingBillAmount() {
        String merchantQr = "upi://pay?pa=store@okicici&pn=Local%20Mart&mc=5411&aid=uGICAgIC";
        PaymentQrParser.ParsedQr parsed = PaymentQrParser.parse(merchantQr);

        String payUri = PaymentQrParser.buildPayUriFromScan(parsed, 142.80, "QR Mart Bill");
        assertTrue(payUri.contains("pa=store@okicici"));
        assertTrue(payUri.contains("mc=5411"));
        assertFalse(payUri.contains("aid="));
        assertTrue(payUri.contains("tr=QRM"));
        assertTrue(payUri.contains("am=142.80"));
        assertTrue(payUri.contains("cu=INR"));
    }

    @Test
    public void buildsCleanMinimalP2pUriWithoutTrForPersonalQr() {
        String personalQr = "upi://pay?pa=shriniketh.kadambi@okaxis&pn=kadambi%20sriniketh&aid=uGICAgMD";
        PaymentQrParser.ParsedQr parsed = PaymentQrParser.parse(personalQr);

        String payUri = PaymentQrParser.buildPayUriFromScan(parsed, 1.05, "QR Mart Bill");
        assertTrue(payUri.contains("pa=shriniketh.kadambi@okaxis"));
        assertTrue(payUri.contains("am=1.05"));
        assertTrue(payUri.contains("cu=INR"));
        assertFalse(payUri.contains("tr="));
        assertFalse(payUri.contains("mc="));
        assertFalse(payUri.contains("aid="));
    }

    @Test
    public void parsesUpiAppResponses() {
        PaymentQrParser.UpiResponse ok = PaymentQrParser.parseUpiResponse(
                "txnId=TXN123&responseCode=00&Status=SUCCESS&ApprovalRefNo=APR999", null);
        assertEquals(PaymentQrParser.UpiStatus.SUCCESS, ok.status);
        assertEquals("APR999", ok.txnId);

        PaymentQrParser.UpiResponse fail = PaymentQrParser.parseUpiResponse("Status=FAILURE", null);
        assertEquals(PaymentQrParser.UpiStatus.FAILURE, fail.status);

        PaymentQrParser.UpiResponse codeOnly = PaymentQrParser.parseUpiResponse(
                "responseCode=00&txnRef=REF42", null);
        assertEquals(PaymentQrParser.UpiStatus.SUCCESS, codeOnly.status);
        assertEquals("REF42", codeOnly.txnId);

        PaymentQrParser.UpiResponse empty = PaymentQrParser.parseUpiResponse(null, null);
        assertEquals(PaymentQrParser.UpiStatus.UNKNOWN, empty.status);
    }

    @Test
    public void formatsReservationCountdownMmSs() {
        assertEquals("05:00", CartReservationManager.formatMmSs(300_000L));
        assertEquals("05:00", CartReservationManager.formatMmSs(299_100L));
        assertEquals("00:59", CartReservationManager.formatMmSs(59_000L));
        assertEquals("00:00", CartReservationManager.formatMmSs(0L));
    }
}
