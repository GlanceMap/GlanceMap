package com.glancemap.trailcore.oam

/** Immutable OpenAndroMaps metadata shared by phone and Wear download flows. */
data class OamDownloadArea(
    val id: String,
    val continent: String,
    val region: String,
    val mapSizeLabel: String,
    val mapSizeBytes: Long,
    val poiSizeLabel: String,
    val poiSizeBytes: Long,
    val notes: String,
    val contourLabel: String,
    val mapZipUrl: String,
    val poiZipUrl: String,
)

object OamDownloadCatalog {
    val areas: List<OamDownloadArea> =
        OAM_CATALOG_ROWS
            .trimIndent()
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { row ->
                val parts = row.split('|')
                val id = parts[0]
                val continent = parts[1]
                val directory = parts[2]
                val fileStem = parts[3]
                val mapSizeBytes = parts[4].toLong()
                val poiSizeBytes = parts[5].toLong()
                OamDownloadArea(
                    id = id,
                    continent = continent,
                    region = fileStem,
                    mapSizeLabel = formatCatalogBytes(mapSizeBytes),
                    mapSizeBytes = mapSizeBytes,
                    poiSizeLabel = formatCatalogBytes(poiSizeBytes),
                    poiSizeBytes = poiSizeBytes,
                    notes = continent,
                    contourLabel = "SRTM1/Lidar 10m",
                    mapZipUrl = "$OAM_MAPS_BASE_URL/$directory/$fileStem.zip",
                    poiZipUrl = "$OAM_POIS_BASE_URL/$directory/$fileStem.Poi.zip",
                )
            }.toList()
}

private fun formatCatalogBytes(bytes: Long): String {
    val mib = bytes / (1024.0 * 1024.0)
    return if (mib < 1024.0) {
        "${mib.roundCatalogSize()} MB"
    } else {
        "${(mib / 1024.0).roundCatalogSize()} GB"
    }
}

private fun Double.roundCatalogSize(): String =
    if (this >= 100.0) {
        java.lang.String.format(java.util.Locale.US, "%.0f", this)
    } else {
        java.lang.String.format(java.util.Locale.US, "%.1f", this)
    }

private const val OAM_MAPS_BASE_URL = "https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/mapsV5"
private const val OAM_POIS_BASE_URL = "https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/pois/mapsforge"

private const val OAM_CATALOG_ROWS = """
africa-algeria|Africa|africa|Algeria|658317735|16033639
africa-angola|Africa|africa|Angola|368704721|2888715
africa-botswana|Africa|africa|Botswana|121446447|1267057
africa-burkinafaso|Africa|africa|BurkinaFaso|165762729|6926448
africa-cameroon|Africa|africa|Cameroon|449079677|5993441
africa-capeverde|Africa|africa|CapeVerde|10947045|593593
africa-centralafricanrep|Africa|africa|CentralAfricanRep|285448002|1610281
africa-chad|Africa|africa|Chad|421211311|4954669
africa-congo-gabon|Africa|africa|Congo-Gabon|347625396|2840819
africa-cote-d-ivoire|Africa|africa|Cote-d-Ivoire|178087363|8921572
africa-dr-congo|Africa|africa|DR-Congo|1165927714|11255937
africa-egypt|Africa|africa|Egypt|449439902|9631748
africa-ethiopiasomalia|Africa|africa|EthiopiaSomalia|894252658|14378451
africa-ghana-togo-benin|Africa|africa|Ghana-Togo-Benin|347669546|14267426
africa-kenya|Africa|africa|Kenya|522834256|12990639
africa-lareunion|Africa|africa|LaReunion|18643681|1887671
africa-libya|Africa|africa|Libya|400798062|5170008
africa-madagascar|Africa|africa|Madagascar|763222156|8127259
africa-malawi|Africa|africa|Malawi|248751387|1826126
africa-mali|Africa|africa|Mali|362113614|6552293
africa-mauritania|Africa|africa|Mauritania|269741366|4307019
africa-mauritius|Africa|africa|Mauritius|6336509|830493
africa-morocco|Africa|africa|Morocco|351865407|12371545
africa-mozambique|Africa|africa|Mozambique|531533884|4570818
africa-namibia|Africa|africa|Namibia|190377885|2195628
africa-niger|Africa|africa|Niger|357699547|5272631
africa-nigeria|Africa|africa|Nigeria|793196841|11909042
africa-seychelles|Africa|africa|Seychelles|25868971|1063445
africa-southafrica|Africa|africa|SouthAfrica|727839049|16880537
africa-sudan|Africa|africa|Sudan|694167807|8128468
africa-tanzania|Africa|africa|Tanzania|962052219|15477577
africa-tunisia|Africa|africa|Tunisia|143765265|6542328
africa-uganda|Africa|africa|Uganda|711638730|19773404
africa-westafrica|Africa|africa|WestAfrica|389060602|13473294
africa-zambia|Africa|africa|Zambia|490455771|5437357
africa-zimbabwe|Africa|africa|Zimbabwe|269819641|1893816
asia-afghanistan|Asia|asia|Afghanistan|880232999|5010034
asia-arab-peninsular|Asia|asia|Arab-Peninsular|1194833787|37043461
asia-bhutan|Asia|asia|Bhutan|118109487|1189171
asia-georgia|Asia|asia|Georgia|481267902|27032888
asia-india-north-east|Asia|asia|India-North-East|1249288359|27475686
asia-india-north-west|Asia|asia|India-North-West|1072149826|40469967
asia-india-south|Asia|asia|India-South|771874032|69663442
asia-iran|Asia|asia|Iran|1023772993|44244746
asia-iraq|Asia|asia|Iraq|212633355|18131165
asia-israel-syria|Asia|asia|Israel-Syria|446359982|32881036
asia-japan|Asia|asia|Japan|1542058413|188199807
asia-kazakhstan|Asia|asia|Kazakhstan|832299994|22303949
asia-korea-south|Asia|asia|Korea-South|355975121|54442172
asia-kyrgyzstan|Asia|asia|Kyrgyzstan|449664415|11355472
asia-malaysia-indonesia-east|Asia|asia|Malaysia-Indonesia-East|740037346|15977618
asia-malaysia-indonesia-west|Asia|asia|Malaysia-Indonesia-West|1832049773|70035455
asia-maldives|Asia|asia|Maldives|2530503|678285
asia-mongolia|Asia|asia|Mongolia|782787996|4080334
asia-myanmar|Asia|asia|Myanmar|862127794|17029479
asia-nepal|Asia|asia|Nepal|605055579|13881067
asia-pakistan|Asia|asia|Pakistan|1005643601|17527409
asia-philippines|Asia|asia|Philippines|560999817|43221385
asia-srilanka|Asia|asia|SriLanka|127860438|6913412
asia-taiwan|Asia|asia|Taiwan|158016017|34281004
asia-tajikistan|Asia|asia|Tajikistan|420783775|4305581
asia-thailand|Asia|asia|Thailand|1401881395|57987996
asia-turkmenistan|Asia|asia|Turkmenistan|193973778|5011450
asia-uzbekistan|Asia|asia|Uzbekistan|322526825|12142245
canada-alberta|Canada|canada|Alberta|758418813|11823555
canada-british-columbia-north|Canada|canada|British-Columbia-North|1167032193|1633346
canada-british-columbia-south|Canada|canada|British-Columbia-South|1605134699|23460516
canada-hudsonbay|Canada|canada|HudsonBay|457628999|872117
canada-manitoba|Canada|canada|Manitoba|236559978|3423402
canada-newbrunswick-novascotia|Canada|canada|NewBrunswick-NovaScotia|266533042|7810921
canada-newfoundland|Canada|canada|Newfoundland|137599028|1616327
canada-nw-territories|Canada|canada|NW-Territories|940763070|645539
canada-ontario|Canada|canada|Ontario|1143712021|59228724
canada-quebec-north|Canada|canada|Quebec-North|933419917|1596605
canada-quebec-south|Canada|canada|Quebec-South|1079218326|33487749
canada-saskatchewan|Canada|canada|Saskatchewan|225975046|2630178
canada-vancouver|Canada|canada|Vancouver|681440484|26115829
canada-yukon|Canada|canada|Yukon|828532873|356974
china-beijing-hebei-shangdong|China|china|Ch-Beijing-Hebei-Shangdong|376710830|33300706
china-gansu-hunan|China|china|Ch-Gansu-Hunan|725600403|9808196
china-guangdong-hainan|China|china|Ch-Guangdong-Hainan|425302743|29078058
china-guangxi-hainan|China|china|Ch-Guangxi-Hainan|530166853|14123592
china-heilongjiang-jilin-liaoning|China|china|Ch-Heilongjiang-Jilin-Liaoning|718278210|44864850
china-henan-hubei|China|china|Ch-Henan-Hubei|420719394|15018798
china-hongkong-macau|China|china|Ch-HongKong-Macau|47726472|13052767
china-jiangsu-anhui-zhejiang|China|china|Ch-Jiangsu-Anhui-Zhejiang|431041429|37388170
china-jiangxi-fujian|China|china|Ch-Jiangxi-Fujian|652553417|47291761
china-mongolia-east|China|china|Ch-Mongolia-East|316280017|4093530
china-mongolia-west|China|china|Ch-Mongolia-West|235003779|4012878
china-qinghai-guizhou-ningxia|China|china|Ch-Qinghai-Guizhou-Ningxia|1106487090|6931061
china-shaanxi-shanxi|China|china|Ch-Shaanxi-Shanxi|640449251|10210492
china-sichuan-chongqing|China|china|Ch-Sichuan-Chongqing|1226655203|10812241
china-tibet|China|china|Ch-Tibet|1328127416|3453846
china-xinjiang|China|china|Ch-Xinjiang|1082870316|3070726
china-yunnan|China|china|Ch-Yunnan|950817626|8917388
europe-alps-east|Europe|europe|Alps-East|1475143821|130351397
europe-alps-west|Europe|europe|Alps-West|1503228435|154409745
europe-alps|Europe|europe|Alps|2912469599|262425652
europe-andorra|Europe|europe|Andorra|28027381|977338
europe-austria|Europe|europe|Austria|1211459771|107803267
europe-azores|Europe|europe|Azores|19057699|1299793
europe-balkan|Europe|europe|Balkan|1182088279|57423810
europe-balticstates|Europe|europe|BalticStates|466884932|36758938
europe-belarus|Europe|europe|Belarus|443500071|41843162
europe-belgium|Europe|europe|Belgium|644067211|69401866
europe-bulgaria|Europe|europe|Bulgaria|244006749|17984375
europe-canaryislands|Europe|europe|CanaryIslands|59645434|6368066
europe-corse|Europe|europe|Corse|63187118|2268360
europe-cyprus|Europe|europe|Cyprus|43719029|4247466
europe-czech-republic|Europe|europe|Czech-Republic|726552737|72566938
europe-denmark|Europe|europe|Denmark|446926486|46631367
europe-estonia|Europe|europe|Estonia|118125211|10290975
europe-faroeislands|Europe|europe|FaroeIslands|12796664|429772
europe-finland|Europe|europe|Finland|1076825387|55826678
europe-france-north|Europe|europe|France-North|1754667595|211769865
europe-france-south|Europe|europe|France-South|2000531980|165821035
europe-great-britain|Europe|europe|Great-Britain|1697281895|183309953
europe-greece|Europe|europe|Greece|446170011|37291611
europe-greenland-discobay|Europe|europe|Greenland-Discobay|349504731|238165
europe-greenland-nuuk|Europe|europe|Greenland-Nuuk|143303327|171224
europe-greenland-south|Europe|europe|Greenland-South|326480223|255704
europe-hungary|Europe|europe|Hungary|411465925|49637547
europe-iceland|Europe|europe|Iceland|244717488|2422953
europe-ireland|Europe|europe|Ireland|326909237|28869553
europe-italy-north|Europe|europe|Italy-North|1439009758|126867661
europe-italy-south|Europe|europe|Italy-South|960845816|70746919
europe-latvia|Europe|europe|Latvia|156473537|15261854
europe-lithuania|Europe|europe|Lithuania|243234026|14864959
europe-luxembourg|Europe|europe|Luxembourg|66618641|6250031
europe-madeira|Europe|europe|Madeira|12108242|1622041
europe-mallorca-ibiza|Europe|europe|Mallorca-Ibiza|35622745|4210876
europe-malta|Europe|europe|Malta|8981599|1632542
europe-netherlands|Europe|europe|Netherlands|875177065|75155417
europe-norway-lofoten|Europe|europe|Norway-Lofoten|137849288|1947014
europe-norway|Europe|europe|Norway|1831097771|51333199
europe-poland|Europe|europe|Poland|1724088681|172902277
europe-portugal|Europe|europe|Portugal|570139108|46492028
europe-pyrenees|Europe|europe|Pyrenees|1063446138|85660676
europe-romania|Europe|europe|Romania|897006087|38444696
europe-sardegna|Europe|europe|Sardegna|113102877|5461075
europe-scandinavia-northeast|Europe|europe|Scandinavia-NorthEast|1717038360|66967807
europe-scandinavia-southwest|Europe|europe|Scandinavia-SouthWest|2257755626|141401120
europe-sicilia|Europe|europe|Sicilia|196713279|12536206
europe-slovakia|Europe|europe|Slovakia|370348605|32633131
europe-slovenia|Europe|europe|Slovenia|512728448|32927664
europe-spain-portugal|Europe|europe|Spain-Portugal|1704013579|189034228
europe-svalbard|Europe|europe|Svalbard|214286500|140822
europe-sweden|Europe|europe|Sweden|1111283790|65325847
europe-switzerland|Europe|europe|Switzerland|804112486|87026208
europe-turkey|Europe|europe|Turkey|1196086073|65605425
europe-uk-ascension|Europe|europe|UK-Ascension|245000|26231
europe-uk-isleofman|Europe|europe|UK-IsleOfMan|4436643|584513
europe-uk-lakedistrict|Europe|europe|UK-LakeDistrict|52544286|3234637
europe-uk-sainthelena|Europe|europe|UK-SaintHelena|519488|27140
europe-uk-scotland|Europe|europe|UK-Scotland|570000227|26466735
europe-uk-shetlands|Europe|europe|UK-Shetlands|33824942|785084
europe-uk-wales|Europe|europe|UK-Wales|211227863|17628682
europe-ukraine|Europe|europe|Ukraine|1211272159|97955951
gaps-bouvet|Gaps|gaps|Bouvet|91515|2920
gaps-ducieeasterprotect|Gaps|gaps|DucieEasterProtect|2449192|157710
gaps-indian-ocean|Gaps|gaps|Indian-Ocean|41374964|128439
gaps-janmayenshetlandgap|Gaps|gaps|JanMayenShetlandGap|8378285|241995
gaps-macquarie|Gaps|gaps|Macquarie|1125097|6923
gaps-rockall|Gaps|gaps|Rockall|69526|998
gaps-stpeterpaultrinidade|Gaps|gaps|StPeterPaulTrinidade|690248|7930
gaps-tristangough|Gaps|gaps|TristanGough|776907|16892
germany-baden-wuerttemberg|Germany|germany|Baden-Wuerttemberg|768242198|120261768
germany-bayern|Germany|germany|Bayern|793683920|110042897
germany-berlin|Germany|germany|Berlin|60194608|20503941
germany-brandenburg|Germany|germany|Brandenburg|227005267|41784374
germany-germany-mid|Germany|germany|Germany-Mid|1606917247|243318876
germany-germany-north|Germany|germany|Germany-North|1865406571|291351538
germany-germany-south|Germany|germany|Germany-South|1512229660|225095503
germany-hamburg|Germany|germany|Hamburg|43483295|11514507
germany-hessen|Germany|germany|Hessen|319914278|55115813
germany-mecklenburg-vorpommern|Germany|germany|Mecklenburg-Vorpommern|152307372|21993261
germany-niedersachsen|Germany|germany|Niedersachsen|520014632|80514790
germany-nordrhein-westfalen|Germany|germany|Nordrhein-Westfalen|646224659|96372730
germany-rheinland-pfalz|Germany|germany|Rheinland-Pfalz|368148490|59485498
germany-ruegen|Germany|germany|Ruegen|8540570|1609381
germany-saarland|Germany|germany|Saarland|233553388|28358226
germany-sachsen-anhalt|Germany|germany|Sachsen-Anhalt|193155354|29306782
germany-sachsen|Germany|germany|Sachsen|239058813|37521526
germany-schleswig-holstein|Germany|germany|Schleswig-Holstein|149290575|27948891
germany-thueringen|Germany|germany|Thueringen|315448178|45787779
oceania-australia-nsw-vic|Oceania|oceania|Australia-NSW-VIC|565595822|42612769
oceania-australia-nt|Oceania|oceania|Australia-NT|141409749|1181848
oceania-australia-qld|Oceania|oceania|Australia-QLD|382649800|15206552
oceania-australia-south|Oceania|oceania|Australia-South|153703807|5921293
oceania-australia-tasmania|Oceania|oceania|Australia-Tasmania|193533190|16584045
oceania-australia-westnorth|Oceania|oceania|Australia-WestNorth|188800616|1029881
oceania-australia-westsouth|Oceania|oceania|Australia-WestSouth|149993468|7166044
oceania-chathamislands|Oceania|oceania|ChathamIslands|1070835|32862
oceania-guam|Oceania|oceania|Guam|3252516|298668
oceania-micronesia|Oceania|oceania|Micronesia|36378284|1193897
oceania-newzealand|Oceania|oceania|NewZealand|478452559|17249549
oceania-papua-new-guinea|Oceania|oceania|Papua-New-Guinea|358456559|2378533
oceania-polynesian-isles|Oceania|oceania|Polynesian-Isles|38989004|1553345
oceania-samoa|Oceania|oceania|Samoa|4264290|385329
oceania-seaeastofaustralia|Oceania|oceania|SeaEastOfAustralia|97396022|1778232
russia-caucasia|Russia|russia|Caucasia|742775995|47685844
russia-central|Russia|russia|Central|879925258|78686177
russia-chukotka|Russia|russia|Chukotka|491789125|147178
russia-far-east-north|Russia|russia|Far-East-North|1063159645|935901
russia-far-east-south-1|Russia|russia|Far-East-South-1|1283753472|10183496
russia-far-east-south-2|Russia|russia|Far-East-South-2|1086001087|1268717
russia-franz-josefs-land|Russia|russia|Franz-Josefs-Land|15578187|15405
russia-mockba|Russia|russia|MOCKBA|64579817|24320054
russia-north-west-1|Russia|russia|North-West-1|574663580|32647388
russia-north-west-2|Russia|russia|North-West-2|291467034|5289761
russia-north-west-3|Russia|russia|North-West-3|219006253|1708213
russia-siberia-north|Russia|russia|Siberia-North|374810857|332532
russia-siberia-south|Russia|russia|Siberia-South|2129292225|22940782
russia-ural-north|Russia|russia|Ural-North|162040391|1352474
russia-ural-south|Russia|russia|Ural-South|430170512|18864697
russia-volga|Russia|russia|Volga|957738018|47095328
southamerica-belize|South America|southamerica|Belize|52144793|1270742
southamerica-bermuda|South America|southamerica|Bermuda|1383160|260615
southamerica-bolivia|South America|southamerica|Bolivia|713310568|21463099
southamerica-brasil-amazonas|South America|southamerica|Brasil-Amazonas|1096474880|9661996
southamerica-brasil-coast-north|South America|southamerica|Brasil-Coast-North|1103237087|39714169
southamerica-brasil-coast-south|South America|southamerica|Brasil-Coast-South|1742382169|85178409
southamerica-brasil-pantanal|South America|southamerica|Brasil-Pantanal|385036433|11272228
southamerica-caribbeansea|South America|southamerica|CaribbeanSea|669297002|47343840
southamerica-chile-argentina-north|South America|southamerica|Chile-Argentina-North|1098425233|66088274
southamerica-chile-argentina-south|South America|southamerica|Chile-Argentina-South|1091737203|53187411
southamerica-colombia|South America|southamerica|Colombia|836203260|28935115
southamerica-costarica|South America|southamerica|CostaRica|86953400|10059613
southamerica-cuba|South America|southamerica|Cuba|64843868|7945729
southamerica-easter-island|South America|southamerica|Easter-Island|501035|164966
southamerica-ecuador|South America|southamerica|Ecuador|331335525|16274284
southamerica-elsalvador|South America|southamerica|ElSalvador|121899359|4175740
southamerica-galapagos|South America|southamerica|Galapagos|4326671|183805
southamerica-guatemala|South America|southamerica|Guatemala|449455755|11021088
southamerica-guyana-suriname|South America|southamerica|Guyana-Suriname|254448227|2406997
southamerica-honduras|South America|southamerica|Honduras|309344156|5930769
southamerica-ile-de-clipperton|South America|southamerica|Ile-de-Clipperton|23263|3397
southamerica-mexico|South America|southamerica|Mexico|1482682243|60827966
southamerica-middle-america|South America|southamerica|Middle-America|677371223|31843859
southamerica-nicaragua|South America|southamerica|Nicaragua|168218448|5977576
southamerica-panama|South America|southamerica|Panama|131496691|3763012
southamerica-paraguay|South America|southamerica|Paraguay|261437842|11618014
southamerica-peru-ecuador|South America|southamerica|Peru-Ecuador|1293320020|44053128
southamerica-south-georgia|South America|southamerica|South-Georgia|10893841|66945
southamerica-uruguay|South America|southamerica|Uruguay|164062726|22454803
southamerica-venezuela|South America|southamerica|Venezuela|577885726|16674381
usa-alaska|USA|usa|Alaska|1515958361|2392325
usa-arizona-newmexico|USA|usa|Arizona-NewMexico|676141898|25034107
usa-california|USA|usa|California|1199670692|63588196
usa-carolina-georgia|USA|usa|Carolina-Georgia|844942830|46569698
usa-colorado|USA|usa|Colorado|473417656|16311278
usa-dakota|USA|usa|Dakota|202940567|4920449
usa-florida|USA|usa|Florida|536205291|28772180
usa-hawaii-incl-small-isles|USA|usa|Hawaii-incl-small-isles|31127999|2498560
usa-hawaii|USA|usa|Hawaii|27658358|2480675
usa-idaho|USA|usa|Idaho|466572766|6232997
usa-illinois-missouri|USA|usa|Illinois-Missouri|538884096|42940601
usa-indiana-ohio-kentucky|USA|usa|Indiana-Ohio-Kentucky|1038176583|77930513
usa-maine|USA|usa|Maine|148660997|7717561
usa-minnesota-iowa|USA|usa|Minnesota-Iowa|462960452|28055862
usa-montana|USA|usa|Montana|422938191|4371740
usa-nebraska-kansas-oklah|USA|usa|Nebraska-Kansas-Oklah|413884097|21757633
usa-nevada|USA|usa|Nevada|335513568|7059567
usa-newyork-plus-east|USA|usa|NewYork-plus-east|943561686|85485859
usa-oregon|USA|usa|Oregon|479377967|14916333
usa-pensylv-newjersey|USA|usa|Pensylv-NewJersey|527573719|51818671
usa-south-mid|USA|usa|South-Mid|789066688|38374763
usa-texas|USA|usa|Texas|621039410|39078103
usa-utah|USA|usa|Utah|361330195|10545101
usa-virg-maryl-dela|USA|usa|Virg-Maryl-Dela|737196095|47645864
usa-washington|USA|usa|Washington|671845231|36782881
usa-wisconsin-michigan|USA|usa|Wisconsin-Michigan|801119651|66498259
usa-wyoming|USA|usa|Wyoming|266559964|2678203
usa-yellowstone-np|USA|usa|Yellowstone-NP|144877687|637917
"""
