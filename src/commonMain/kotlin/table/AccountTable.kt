package table

import model.Account
import model.AccountTableImpl
import neton.database.api.Table

object AccountTable : Table<Account, Long> by AccountTableImpl
