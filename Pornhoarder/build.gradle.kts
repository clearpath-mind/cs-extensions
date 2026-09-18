version = 25

android {
    namespace = "com.pornhoarder"
}

cloudstream {
    authors = listOf("clearpath-mind")
    language = "en"
    description = "PornHoarder - mirror domain with Cloudflare handling"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("NSFW")
    iconUrl = "https://www.google.com/s2/favicons?domain=ww8.pornhoarder.org&sz=128"
}
