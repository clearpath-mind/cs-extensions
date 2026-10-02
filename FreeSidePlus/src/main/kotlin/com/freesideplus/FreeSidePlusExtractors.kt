package com.freesideplus

import com.lagradost.cloudstream3.extractors.ByseSX
import com.lagradost.cloudstream3.extractors.DoodLaExtractor

/**
 * Same-host mirror subclasses so Byse/Dood embeds resolve even when the
 * host app's bundled extractor registry predates these mirrors.
 * (Streamly/Extractors.kt follows the same pattern.)
 */
class DoodDsvplay : DoodLaExtractor() {
    override var mainUrl = "https://dsvplay.com"
}

class ByseFsp : ByseSX() {
    override val mainUrl = "https://bysevepoin.com"
}
