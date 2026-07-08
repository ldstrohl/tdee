package com.tdee.domain

/** Display-edge conversions. Canonical unit everywhere in the engine is kg. */
const val KG_TO_LB = 2.2046226
fun kgToLb(kg: Double): Double = kg * KG_TO_LB
fun lbToKg(lb: Double): Double = lb / KG_TO_LB
