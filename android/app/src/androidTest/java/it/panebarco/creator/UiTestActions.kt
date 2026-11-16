package it.panebarco.creator

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Keep the real hit test: an inner horizontal row must not leave a tap outside its parent viewport. */
internal fun ComposeTestRule.clickVisibleControl(tag: String, density: Float) {
    val node = onNodeWithTag(tag)
    val ancestors = generateSequence(node.fetchSemanticsNode().parent) { it.parent }
    if (ancestors.any { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) || it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }) node.performScrollTo()
    repeat(8) {
        if (node.fullyVisible(density)) { node.assertIsEnabled().performClick(); waitForIdle(); return }
        val viewport = generateSequence(node.fetchSemanticsNode().parent) { it.parent }
            .firstOrNull { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
            ?: throw AssertionError("Control '$tag' is not fully visible and has no vertical scroll viewport")
        val target = node.getUnclippedBoundsInRoot()
        val targetCenterY = (target.top.value + target.bottom.value) * .5f * density
        val below = targetCenterY > viewport.boundsInRoot.center.y
        onNode(SemanticsMatcher("vertical viewport for $tag") { it.id == viewport.id }, useUnmergedTree = true)
            .performTouchInput {
                val x = width * .02f
                swipe(Offset(x, height * if (below) .8f else .2f), Offset(x, height * if (below) .2f else .8f), durationMillis = 300)
            }
        waitForIdle()
    }
    throw AssertionError("Control '$tag' did not become fully visible after 8 public scroll gestures")
}

internal fun SemanticsNodeInteraction.fullyVisible(density: Float): Boolean {
    if (runCatching { assertIsDisplayed() }.isFailure) return false
    val visible = fetchSemanticsNode().boundsInRoot
    val full = getUnclippedBoundsInRoot()
    return visible.left <= full.left.value * density + 1f && visible.top <= full.top.value * density + 1f &&
        visible.right + 1f >= full.right.value * density && visible.bottom + 1f >= full.bottom.value * density
}
