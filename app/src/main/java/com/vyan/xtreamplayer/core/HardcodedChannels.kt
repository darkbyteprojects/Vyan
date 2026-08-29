package com.vyan.xtreamplayer.core

import androidx.compose.ui.graphics.Color

data class HardcodedChannel(
    val id: String,
    val name: String,
    val keywords: List<String>,
    val gradient: List<Color>,
    val exclude: List<String> = emptyList(),
    val filters: List<String>
)

object ThemePalettes {
    val all = listOf(
        listOf(Color(0xFF7C3AED), Color(0xFF22D3EE)), listOf(Color(0xFFEF4444), Color(0xFFF59E0B)),
        listOf(Color(0xFFB91C1C), Color(0xFF1F2937)), listOf(Color(0xFFDC2626), Color(0xFF111827)),
        listOf(Color(0xFFF97316), Color(0xFF7C2D12)), listOf(Color(0xFF1D4ED8), Color(0xFF22C55E)),
        listOf(Color(0xFF0EA5E9), Color(0xFF1E293B)), listOf(Color(0xFFEC4899), Color(0xFF8B5CF6)),
        listOf(Color(0xFFFBBF24), Color(0xFFF472B6)), listOf(Color(0xFF14B8A6), Color(0xFF0F766E)),
        listOf(Color(0xFFF59E0B), Color(0xFFDB2777)), listOf(Color(0xFF059669), Color(0xFF064E3B))
    )
}

object HardcodedChannels {
    val allRegions = listOf("All", "International", "India", "US", "UK", "MENA", "Europe", "LATAM", "Canada", "AUS/NZ")

    val all = listOf(
        HardcodedChannel("culver_max", "Sony", listOf("sony set", "sony entertainment", "set hd", "sony sab", "sony pal", "sony max", "sony max 2", "sony wah", "sony sports", "sony ten 1", "sony ten 2", "sony ten 3", "sony ten 4", "sony ten 5", "sony six", "sony pix", "sony bbc earth", "sony yay", "sony marathi", "sony aath"), ThemePalettes.all[10], emptyList(), listOf("India")),
        HardcodedChannel("disney_star", "Star", listOf("star plus", "star+", "star bharat", "star utsav", "star gold", "star gold 2", "star gold romance", "star gold thrills", "star utsav movies", "star movies", "star world", "star sports 1", "star sports 2", "star sports 3", "star sports select", "star sports hindi", "star sports tamil", "star sports telugu", "star sports kannada", "national geographic", "nat geo wild", "disney channel", "hungama tv", "super hungama", "disney junior", "star pravah", "star jalsha", "jalsha movies", "star vijay", "vijay super", "star suvarna", "suvarna plus", "star maa", "maa movies", "star kiran", "asianet", "asianet movies"), ThemePalettes.all[0], emptyList(), listOf("India")),
        HardcodedChannel("zeel_network", "Zee", listOf("zee tv", "&tv", "zee anmol", "big magic", "zee cinema", "&pictures", "zee bollywood", "zee action", "zee classic", "zee anmol cinema", "zee cafe", "zee zest", "zing", "zee marathi", "zee talkies", "zee bangla", "zee telugu", "zee cinemalu", "zee kannada", "zee tamil", "zee thirai", "zee keralam", "zee sarthak", "zee punjabi", "zee ganga", "zee biskope"), ThemePalettes.all[7], emptyList(), listOf("India")),
        HardcodedChannel("viacom18_network", "Viacom18 (Colors)", listOf("colors tv", "colors rishtey", "colors cineplex", "colors cineplex bollywood", "rishtey cineplex", "colors infinity", "comedy central", "mtv india", "mtv beats", "vh1 india", "nickelodeon", "sonic", "nick jr", "history tv18", "sports18", "sports 18", "sports18 1", "sports18 2", "sports18 khel", "colors marathi", "colors bangla", "colors kannada", "colors super", "colors tamil", "colors gujarati", "colors odia"), ThemePalettes.all[1], emptyList(), listOf("India")),
        HardcodedChannel("sun_tv_network", "Sun TV", listOf("sun tv", "ktv", "sun music", "sun news", "chutti tv", "adithya tv", "sun life", "gemini tv", "gemini movies", "gemini music", "gemini comedy", "kushi tv", "gemini life", "udaya tv", "udaya movies", "udaya music", "udaya comedy", "chintu tv", "surya tv", "surya movies", "surya music", "surya comedy", "kochu tv", "sun bangla", "sun marathi", "sun neo"), ThemePalettes.all[5], emptyList(), listOf("India")),
        HardcodedChannel("doordarshan_network", "Doordarshan", listOf("dd national", "dd news", "dd sports", "dd india", "dd kisan", "dd bharati", "dd retro", "dd urdu", "dd bangla", "dd chandana", "dd sahyadri", "dd girnar", "dd podhigai", "dd malayalam", "dd saptagiri", "dd yadagiri", "dd punjabi", "dd kashir", "dd bihar", "dd rajasthan", "dd up", "dd mp"), ThemePalettes.all[6], emptyList(), listOf("India")),
        HardcodedChannel("discovery_warner_india", "Discovery", listOf("discovery channel", "animal planet", "tlc", "investigation discovery", "discovery science", "discovery turbo", "cartoon network", "pogo", "discovery kids", "eurosport"), ThemePalettes.all[9], emptyList(), listOf("India")),
        HardcodedChannel("times_network", "Times", listOf("times now", "mirror now", "et now", "times now navbharat", "et now swadesh", "zoom", "movies now", "mnx", "romedy now"), ThemePalettes.all[6], emptyList(), listOf("India")),
        HardcodedChannel("tv_today_network", "India Today", listOf("aaj tak", "aajtak", "india today news", "good news today", "gnt news"), ThemePalettes.all[4], emptyList(), listOf("India")),
        HardcodedChannel("abp_network", "ABP News", listOf("abp news", "abp ananda", "abp majha", "abp asmita", "abp ganga", "abp sanjha", "abp nadu"), ThemePalettes.all[6], emptyList(), listOf("India")),
        HardcodedChannel("ndtv_network", "NDTV", listOf("ndtv 24x7", "ndtv india", "ndtv profit", "ndtv rajasthan", "ndtv mp", "ndtv marathi"), ThemePalettes.all[6], emptyList(), listOf("India")),
        HardcodedChannel("b4u_network", "B4U", listOf("b4u movies", "b4u music", "b4u kadak", "b4u bhojpuri", "b4u aflatoon"), ThemePalettes.all[8], emptyList(), listOf("India")),
        HardcodedChannel("in10_media_network", "IN10 Media", listOf("epic tv", "ishara", "showbox", "filamchi", "gubbare"), ThemePalettes.all[11], emptyList(), listOf("India")),
        HardcodedChannel("ninex_media", "9X Media", listOf("9xm", "9x jalwa", "9x jhakaas", "9x tashan"), ThemePalettes.all[8], emptyList(), listOf("India")),
        HardcodedChannel("etv_network", "ETV", listOf("etv telugu", "etv plus", "etv cinema", "etv life", "etv abhiruchi", "etv telangana", "etv andhra pradesh", "etv bal bharat"), ThemePalettes.all[10], emptyList(), listOf("India")),
        HardcodedChannel("fta_heartland_leaders", "FTA & Heartland", listOf("dangal tv", "dangal 2", "enterr10", "bhojpuri cinema", "shemaroo tv", "shemaroo umang", "shemaroo marathibana", "shemaroo filmi gaane", "goldmines tv", "goldmines movies", "dhinchaak", "mastiii", "dabang", "dhamaal"), ThemePalettes.all[4], emptyList(), listOf("India")),
        HardcodedChannel("south_regional_independents", "South Regional", listOf("raj tv", "raj digital plus", "raj news", "kalaignar tv", "seithigal", "isai aruvi", "jaya tv", "jaya plus", "polimer tv", "polimer news", "mazhavil manorama", "manorama news", "flowers tv", "24 news", "ntv news", "sakshi tv", "v6 news", "t news", "public tv", "public music", "public movies"), ThemePalettes.all[11], emptyList(), listOf("India")),
        HardcodedChannel("north_east_west_independents", "North/East/West", listOf("odisha tv", "otv", "tarang tv", "tarang music", "alankar", "prarthana tv", "ptc punjabi", "ptc news", "ptc music", "ptc chakde", "ptc simran", "pratidin time", "news live", "rang tv", "ramdhenu tv"), ThemePalettes.all[11], emptyList(), listOf("India")),
        HardcodedChannel("devotional_networks", "Devotional & Spiritual", listOf("aastha tv", "aastha bhajan", "aastha tamil", "sanskar tv", "satsang tv", "sadhna tv", "ishwar tv"), ThemePalettes.all[11], emptyList(), listOf("India")),
        HardcodedChannel("discovery", "Discovery Intl", listOf("discovery", "discovery+", "discovery channel"), ThemePalettes.all[9], listOf("kids"), listOf("International", "US", "UK", "Europe", "LATAM")),
        HardcodedChannel("nat_geo", "Nat Geo Intl", listOf("national geographic", "nat geo", "natgeo wild"), ThemePalettes.all[9], emptyList(), listOf("International", "US", "UK", "Europe", "MENA", "LATAM")),
        HardcodedChannel("history", "History Intl", listOf("history channel", "history hd", "history us", "history uk"), ThemePalettes.all[9], emptyList(), listOf("International", "US", "UK", "Europe")),
        HardcodedChannel("animal_planet", "Animal Planet Intl", listOf("animal planet"), ThemePalettes.all[9], emptyList(), listOf("International", "US", "UK")),
        HardcodedChannel("cnn", "CNN", listOf("cnn", "cnn international", "cnn hd"), ThemePalettes.all[6], emptyList(), listOf("International", "US")),
        HardcodedChannel("bbc_news", "BBC News", listOf("bbc news", "bbc world", "bbc world news"), ThemePalettes.all[6], emptyList(), listOf("International", "UK")),
        HardcodedChannel("al_jazeera", "Al Jazeera", listOf("al jazeera", "aljazeera", "jazeera", "al jazeera english", "al jazeera arabic"), ThemePalettes.all[6], emptyList(), listOf("International", "MENA")),
        HardcodedChannel("cartoon_network", "Cartoon Network", listOf("cartoon network", "cartoonnetwork", "cn hd"), ThemePalettes.all[8], emptyList(), listOf("International", "US", "UK", "LATAM")),
        HardcodedChannel("disney", "Disney", listOf("disney channel", "disney hd", "disney xd", "disney junior"), ThemePalettes.all[8], listOf("disney+", "disney plus"), listOf("International", "US", "UK", "LATAM")),
        HardcodedChannel("nickelodeon", "Nickelodeon", listOf("nickelodeon", "nick jr", "nick hd", "nicktoons"), ThemePalettes.all[8], emptyList(), listOf("International", "US", "UK", "LATAM")),
        HardcodedChannel("hbo", "HBO", listOf("hbo", "hbo max", "max originals", "hbo signature", "hbo family"), ThemePalettes.all[7], emptyList(), listOf("International", "US", "LATAM", "Europe")),
        HardcodedChannel("mtv", "MTV", listOf(" mtv ", "mtv hd", "mtv usa", "mtv uk", "mtv live", "mtv 80s", "mtv 90s"), ThemePalettes.all[8], emptyList(), listOf("International", "US", "UK", "Europe", "LATAM")),
        HardcodedChannel("ufc", "UFC", listOf("ufc", "ufc fight pass", "ufc ppv", "ufc fight night", "ufc apex"), ThemePalettes.all[4], emptyList(), listOf("International", "US", "UK", "AUS/NZ")),
        HardcodedChannel("wwe", "WWE", listOf("wwe", "wwe network", "wwe raw", "wwe smackdown", "wrestlemania", "summerslam"), ThemePalettes.all[2], emptyList(), listOf("International", "US", "India", "UK")),
        HardcodedChannel("aew", "AEW", listOf("aew", "all elite wrestling", "aew dynamite", "aew rampage"), ThemePalettes.all[2], emptyList(), listOf("International", "US", "UK")),
        HardcodedChannel("boxing", "Boxing", listOf("boxing", "ppv box", "fight night", "matchroom", "top rank", "dazn boxing"), ThemePalettes.all[4], listOf("box office", "xbox", "boxset"), listOf("International", "US", "UK")),
        HardcodedChannel("f1", "Formula 1", listOf("f1 tv", "formula 1", "formula one", "sky f1", "skysports f1", "grand prix"), ThemePalettes.all[3], emptyList(), listOf("International", "UK", "Europe", "MENA", "US")),
        HardcodedChannel("motogp", "MotoGP", listOf("motogp", "moto gp", "moto2", "moto3"), ThemePalettes.all[3], emptyList(), listOf("International", "Europe", "LATAM")),
        HardcodedChannel("champions_league", "Champions League", listOf("champions league", "uefa champions", "ucl", "europa league"), ThemePalettes.all[0], emptyList(), listOf("International", "Europe", "MENA", "UK", "US")),
        HardcodedChannel("premier_league", "Premier League", listOf("premier league", "epl", "barclays premier", "sky sports pl"), ThemePalettes.all[0], emptyList(), listOf("International", "UK", "US", "MENA")),
        HardcodedChannel("us_major_networks", "US Broadcast", listOf("abc hd", "abc local", "cbs hd", "cbs local", "nbc hd", "nbc local", "fox local", "fox east", "fox west"), ThemePalettes.all[6], emptyList(), listOf("US")),
        HardcodedChannel("us_sports_networks", "US Sports", listOf("espn", "espn2", "espnews", "espn+", "fox sport", "fs1", "fs2", "nbc sport", "nbcsn", "cbs sport", "tnt usa", "nba tv", "nfl network", "redzone", "mlb network", "nhl network"), ThemePalettes.all[1], emptyList(), listOf("US")),
        HardcodedChannel("uk_bbc_itv_sky", "UK Broadcast", listOf("bbc one", "bbc two", "itv1", "itv2", "itv3", "itv4", "channel 4", "channel 5", "sky news uk"), ThemePalettes.all[5], emptyList(), listOf("UK")),
        HardcodedChannel("uk_sports_networks", "UK Sports", listOf("sky sport", "sky sports main event", "sky sports football", "sky sports f1", "sky sports cricket", "tnt sport", "tnt sports 1", "tnt sports 2", "bt sport"), ThemePalettes.all[5], emptyList(), listOf("UK")),
        HardcodedChannel("mena_sports_entertainment", "MENA Networks", listOf("bein sport", "bein max", "mbc1", "mbc2", "mbc action", "rotana", "osn", "ssc sports", "abu dhabi sport", "dubai sport", "al arabiya"), ThemePalettes.all[11], emptyList(), listOf("MENA")),
        HardcodedChannel("europe_sports_entertainment", "Europe Networks", listOf("eurosport 1", "eurosport 2", "canal+", "canal+ sport", "rmc sport", "dazn", "sky calcio", "sky bundesliga", "movistar", "rai 1", "tf1", "rtl"), ThemePalettes.all[0], emptyList(), listOf("Europe")),
        HardcodedChannel("latam_networks", "LATAM Networks", listOf("telemundo", "univision", "tudn", "espn deportes", "fox sports argentina", "directv sports", "dsports", "tyc sports", "tv globo", "televisa"), ThemePalettes.all[10], emptyList(), listOf("LATAM")),
        HardcodedChannel("canada_networks", "Canada Networks", listOf("tsn1", "tsn2", "tsn3", "sportsnet", "cbc news", "ctv news", "global tv"), ThemePalettes.all[1], emptyList(), listOf("Canada")),
        HardcodedChannel("aus_nz_networks", "AUS/NZ Networks", listOf("fox league", "fox footy", "channel 9", "channel 7", "abc news au", "optus sport", "sky sport nz"), ThemePalettes.all[5], emptyList(), listOf("AUS/NZ"))
    )

    fun matches(streamName: String, keywords: List<String>, exclude: List<String> = emptyList()): Boolean {
        val lower = streamName.lowercase()
        for (ex in exclude) if (lower.contains(ex.lowercase())) return false
        for (k in keywords) if (lower.contains(k.lowercase())) return true
        return false
    }
}