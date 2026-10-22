package com.veilframe.app.pdf;

import org.junit.Assert;
import org.junit.Test;

public class AdobeCoolTypeEngineTest {

    @Test
    public void testCoolTypeMetricsDeterministic() {
        float widthBold = AdobeCoolTypeEngine.measureExactWidth("Signature valid", 11.72f, true);
        float widthReg = AdobeCoolTypeEngine.measureExactWidth("Signature valid", 11.72f, false);

        Assert.assertTrue("Bold Helvetica must be wider than regular Helvetica", widthBold > widthReg);
        Assert.assertTrue("Signature valid width must be in expected range (~85-95 pt)", widthBold > 80f && widthBold < 100f);
    }

    @Test
    public void testAnchorCrossesAOfValidAndNeverD() {
        float titleX = 2.0f;
        float fontSize = 11.72f;
        String text = "Signature valid";

        float anchorX = AdobeCoolTypeEngine.calculateAnchorInsideAOfValid(text, titleX, fontSize);

        // Calculate exact positions of 'a' and 'd'
        float prefixWidth = AdobeCoolTypeEngine.measureExactWidth("Signature ", fontSize, true);
        float vWidth = AdobeCoolTypeEngine.measureExactWidth("v", fontSize, true);
        float aWidth = AdobeCoolTypeEngine.measureExactWidth("a", fontSize, true);
        float lWidth = AdobeCoolTypeEngine.measureExactWidth("l", fontSize, true);
        float iWidth = AdobeCoolTypeEngine.measureExactWidth("i", fontSize, true);
        float dWidth = AdobeCoolTypeEngine.measureExactWidth("d", fontSize, true);

        float aStart = titleX + prefixWidth + vWidth;
        float aEnd = aStart + aWidth;
        float dStart = aEnd + lWidth + iWidth;
        float dEnd = dStart + dWidth;

        // Verify anchor is inside 'a'
        Assert.assertTrue("Anchor X must be >= start of 'a'", anchorX >= aStart);
        Assert.assertTrue("Anchor X must be <= end of 'a'", anchorX <= aEnd);

        // Verify anchor NEVER reaches 'd'
        Assert.assertTrue("Anchor X must be far before start of 'd'", anchorX < dStart);
        Assert.assertTrue("Distance between anchor and 'd' must be >= 5 pt", (dStart - anchorX) >= 5.0f);
    }

    @Test
    public void testAnchorInsideSignatureBetweenUAndR() {
        float titleX = 2.0f;
        float fontSize = 4.7f;
        String text = "Signature valid";

        float anchorX = AdobeCoolTypeEngine.calculateAnchorInsideSignatureBetweenUAndR(text, titleX, fontSize);

        float signatuWidth = AdobeCoolTypeEngine.measureExactWidth("Signatu", fontSize, true);
        float signatureWidth = AdobeCoolTypeEngine.measureExactWidth("Signature", fontSize, true);

        float uEnd = titleX + signatuWidth;
        float sigEnd = titleX + signatureWidth;

        // Verify anchor terminates inside 'Signature' before 'valid'
        Assert.assertTrue("Anchor must be inside 'Signature' after 'u'", anchorX >= uEnd - 0.5f);
        Assert.assertTrue("Anchor must be before end of 'Signature'", anchorX <= sigEnd);

        // Verify it never reaches 'valid'
        float validStart = titleX + AdobeCoolTypeEngine.measureExactWidth("Signature ", fontSize, true);
        Assert.assertTrue("Anchor must be well before 'valid'", anchorX < validStart);
    }
}
