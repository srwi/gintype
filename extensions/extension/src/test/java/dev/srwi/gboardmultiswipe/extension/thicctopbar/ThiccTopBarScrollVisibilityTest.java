package dev.srwi.gboardmultiswipe.extension.thicctopbar;

import org.junit.Assert;
import org.junit.Test;

public final class ThiccTopBarScrollVisibilityTest {

    @Test
    public void keepsScrollUnchangedWhenColumnAlreadyCompletelyVisible() {
        int scrollX = ThiccTopBarView.computeEnsureVisibleScrollX(
                700, 900, 500, 1000, 2000);
        Assert.assertEquals(500, scrollX);
    }

    @Test
    public void keepsScrollUnchangedWhenColumnTouchesViewportEdgesExactly() {
        int scrollX = ThiccTopBarView.computeEnsureVisibleScrollX(
                500, 1500, 500, 1000, 2000);
        Assert.assertEquals(500, scrollX);
    }

    @Test
    public void scrollsLeftMinimallyWhenColumnClippedOnLeft() {
        int scrollX = ThiccTopBarView.computeEnsureVisibleScrollX(
                420, 620, 500, 1000, 2000);
        Assert.assertEquals(420, scrollX);
    }

    @Test
    public void scrollsLeftMinimallyWhenColumnCompletelyOffscreenLeft() {
        int scrollX = ThiccTopBarView.computeEnsureVisibleScrollX(
                100, 250, 500, 1000, 2000);
        Assert.assertEquals(100, scrollX);
    }

    @Test
    public void scrollsRightMinimallyWhenColumnClippedOnRight() {
        int scrollX = ThiccTopBarView.computeEnsureVisibleScrollX(
                1400, 1650, 500, 1000, 2000);
        Assert.assertEquals(650, scrollX);
    }

    @Test
    public void scrollsRightMinimallyWhenColumnCompletelyOffscreenRight() {
        int scrollX = ThiccTopBarView.computeEnsureVisibleScrollX(
                1700, 1900, 0, 1000, 2000);
        Assert.assertEquals(900, scrollX);
    }

    @Test
    public void alignsLeftWhenColumnWiderThanViewport() {
        int scrollX = ThiccTopBarView.computeEnsureVisibleScrollX(
                150, 350, 0, 100, 500);
        Assert.assertEquals(150, scrollX);

        int unchanged = ThiccTopBarView.computeEnsureVisibleScrollX(
                150, 350, 180, 100, 500);
        Assert.assertEquals(180, unchanged);
    }

    @Test
    public void clampsToZeroWhenContentFitsInViewport() {
        int scrollX = ThiccTopBarView.computeEnsureVisibleScrollX(
                100, 300, 0, 1000, 600);
        Assert.assertEquals(0, scrollX);
    }
}
