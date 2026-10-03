package com.mtgofa.carinfo.obd

import java.util.Calendar

data class VinInfo(val make: String?, val country: String?, val year: Int?)

/** Decodes make, country and model year from a 17-char VIN using the WMI and 10th character. */
object Vin {
    private val wmi3 = mapOf(
        // China
        "LVV" to "Chery", "LNN" to "Chery", "LVT" to "Chery", "LGW" to "Haval / Great Wall", "LGX" to "BYD",
        "LC0" to "BYD", "L6T" to "Geely", "LB3" to "Geely", "LSJ" to "MG", "LSG" to "Chevrolet (SAIC-GM)",
        "LSV" to "Volkswagen (SAIC)", "LFV" to "Volkswagen (FAW)", "LFM" to "Toyota (FAW)", "LTV" to "Toyota (FAW)",
        "LVG" to "Toyota (GAC)", "LHG" to "Honda (GAC)", "LMG" to "GAC Trumpchi", "LS5" to "Changan",
        "LVS" to "Ford (Changan)", "LZW" to "Wuling (SGMW)", "LJD" to "Kia (Dongfeng Yueda)",
        "LBV" to "BMW Brilliance", "LDC" to "Peugeot / Citroën (Dongfeng)", "LNB" to "BAIC",
        "LJ1" to "JAC", "LRW" to "Tesla", "LFP" to "FAW", "LDN" to "Soueast", "LBE" to "Hyundai (Beijing)",
        "LGB" to "Nissan (Dongfeng)", "LGJ" to "Dongfeng", "LUR" to "Chery Jaecoo / Omoda",
        // Korea
        "KMH" to "Hyundai", "KM8" to "Hyundai", "KMF" to "Hyundai", "KNA" to "Kia", "KND" to "Kia",
        "KNM" to "Renault Samsung", "KL1" to "Chevrolet (GM Korea)", "KLA" to "Daewoo / Chevrolet",
        "KPT" to "SsangYong", "KPA" to "SsangYong",
        // Japan
        "JHM" to "Honda", "JHL" to "Honda", "JMB" to "Mitsubishi", "JMZ" to "Mazda", "JM1" to "Mazda",
        "JN1" to "Nissan", "JN8" to "Nissan", "JF1" to "Subaru", "JF2" to "Subaru", "JS2" to "Suzuki",
        "JS3" to "Suzuki", "JSA" to "Suzuki", "JA3" to "Mitsubishi", "JA4" to "Mitsubishi", "JTD" to "Toyota",
        "JTE" to "Toyota", "JTH" to "Lexus", "JTJ" to "Lexus", "JTM" to "Toyota", "JTN" to "Toyota",
        "JT2" to "Toyota", "JT3" to "Toyota", "JN6" to "Nissan",
        // Europe
        "WVW" to "Volkswagen", "WV1" to "Volkswagen Commercial", "WV2" to "Volkswagen Commercial",
        "WVG" to "Volkswagen", "WAU" to "Audi", "WUA" to "Audi", "WBA" to "BMW", "WBS" to "BMW M",
        "WBY" to "BMW i", "WMW" to "MINI", "WDB" to "Mercedes-Benz", "WDD" to "Mercedes-Benz",
        "WDC" to "Mercedes-Benz", "W1K" to "Mercedes-Benz", "W1N" to "Mercedes-Benz", "WP0" to "Porsche",
        "WP1" to "Porsche", "W0L" to "Opel", "W0V" to "Opel", "WF0" to "Ford (Germany)", "VF1" to "Renault",
        "VF3" to "Peugeot", "VF7" to "Citroën", "VR3" to "Peugeot", "VR1" to "DS", "VR7" to "Citroën",
        "VSS" to "SEAT", "VNK" to "Toyota (France)", "VSK" to "Nissan (Spain)", "TMB" to "Škoda",
        "TMA" to "Hyundai (Czech)", "TSM" to "Suzuki (Hungary)", "U5Y" to "Kia (Slovakia)", "UU1" to "Dacia",
        "ZFA" to "Fiat", "ZAR" to "Alfa Romeo", "ZFF" to "Ferrari", "ZHW" to "Lamborghini", "ZAM" to "Maserati",
        "SAL" to "Land Rover", "SAJ" to "Jaguar", "SCC" to "Lotus", "SCF" to "Aston Martin",
        "SJN" to "Nissan (UK)", "SHH" to "Honda (UK)", "SB1" to "Toyota (UK)", "YV1" to "Volvo",
        "YV4" to "Volvo", "YS3" to "Saab", "XTA" to "Lada", "NM0" to "Ford (Turkey)", "NMT" to "Toyota (Turkey)",
        "NLH" to "Hyundai (Turkey)", "NM4" to "Tofaş / Fiat (Turkey)", "VF8" to "Renault (Turkey)",
        // Americas
        "1G1" to "Chevrolet", "1GC" to "Chevrolet Truck", "1GN" to "Chevrolet", "1FA" to "Ford",
        "1FT" to "Ford Truck", "1FM" to "Ford", "1HG" to "Honda", "2HG" to "Honda (Canada)",
        "1N4" to "Nissan", "3N1" to "Nissan (Mexico)", "3VW" to "Volkswagen (Mexico)", "1C4" to "Chrysler / Jeep",
        "1J4" to "Jeep", "1J8" to "Jeep", "1C6" to "RAM", "2T1" to "Toyota (Canada)", "4T1" to "Toyota",
        "5YJ" to "Tesla", "7SA" to "Tesla", "5NP" to "Hyundai (USA)", "5XY" to "Kia (USA)", "9BW" to "Volkswagen (Brazil)",
        "9BG" to "Chevrolet (Brazil)", "93H" to "Honda (Brazil)",
        // Asia others
        "MA1" to "Mahindra", "MAL" to "Hyundai (India)", "MA3" to "Suzuki (Maruti)", "MAT" to "Tata",
        "MR0" to "Toyota (Thailand)", "MR1" to "Toyota (Thailand)", "MMB" to "Mitsubishi (Thailand)",
        "MNT" to "Nissan (Thailand)", "MPA" to "Isuzu", "MM8" to "Mazda (Thailand)", "MHR" to "Honda (Indonesia)",
        "MHF" to "Toyota (Indonesia)", "MK2" to "Mitsubishi (Indonesia)", "PL1" to "Proton", "PM1" to "Proton",
        "PMH" to "Perodua",
    )

    private val wmi2 = mapOf(
        "JT" to "Toyota", "JN" to "Nissan", "JH" to "Honda", "JM" to "Mazda", "JS" to "Suzuki",
        "JA" to "Mitsubishi", "JF" to "Subaru", "KM" to "Hyundai", "KN" to "Kia", "KL" to "Chevrolet (GM Korea)",
        "WV" to "Volkswagen", "WB" to "BMW", "WD" to "Mercedes-Benz", "VF" to "Renault / PSA",
        "1G" to "General Motors", "1F" to "Ford", "1C" to "Chrysler", "3G" to "General Motors (Mexico)",
    )

    fun country(c: Char, d: Char): String? = when (c) {
        '1', '4', '5' -> "USA"
        '2' -> "Canada"
        '3' -> "Mexico"
        '6' -> "Australia"
        '8' -> "Argentina"
        '9' -> "Brazil"
        'A' -> "South Africa"
        'J' -> "Japan"
        'K' -> if (d in 'L'..'R') "South Korea" else "Asia"
        'L' -> "China"
        'M' -> when (d) {
            in 'A'..'E' -> "India"; in 'F'..'K' -> "Indonesia"; in 'L'..'R' -> "Thailand"; else -> "Asia"
        }
        'N' -> if (d in 'L'..'R') "Turkey" else "Iran"
        'P' -> "Malaysia"
        'S' -> if (d in 'A'..'M') "United Kingdom" else "Poland"
        'T' -> when (d) { in 'J'..'P' -> "Czech Republic"; in 'R'..'V' -> "Hungary"; else -> "Switzerland" }
        'U' -> "Romania / Slovakia"
        'V' -> if (d in 'A'..'E') "Austria" else if (d in 'F'..'R') "France" else "Spain"
        'W' -> "Germany"
        'X' -> "Russia"
        'Y' -> if (d in 'A'..'E') "Belgium" else if (d in 'S'..'W') "Sweden" else "Finland / Sweden"
        'Z' -> "Italy"
        else -> null
    }

    private const val YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY123456789"

    fun decode(vin: String): VinInfo {
        if (vin.length != 17) return VinInfo(null, null, null)
        val make = wmi3[vin.substring(0, 3)] ?: wmi2[vin.substring(0, 2)]
        val idx = YEAR_CODES.indexOf(vin[9])
        val year = if (idx < 0) null else {
            val latest = Calendar.getInstance().get(Calendar.YEAR) + 1
            // The code cycles every 30 years (1980+, 2010+, 2040+); take the most recent one not in the future.
            generateSequence(1980 + idx) { it + 30 }.takeWhile { it <= latest }.lastOrNull()
        }
        return VinInfo(make, country(vin[0], vin[1]), year)
    }
}
