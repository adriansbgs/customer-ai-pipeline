package com.stara

import java.sql.Connection
import java.sql.DriverManager

class Db(private val url: String, private val user: String, private val password: String) {
    // ponytail: one connection per call, switch to a pool (HikariCP) if traffic grows
    fun <T> use(block: (Connection) -> T): T = DriverManager.getConnection(url, user, password).use(block)
}
