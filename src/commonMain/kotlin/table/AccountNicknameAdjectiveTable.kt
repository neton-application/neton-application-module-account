package table

import model.AccountNicknameAdjective
import model.AccountNicknameAdjectiveTableImpl
import neton.database.api.Table

object AccountNicknameAdjectiveTable : Table<AccountNicknameAdjective, Long> by AccountNicknameAdjectiveTableImpl
