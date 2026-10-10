package table

import model.AccountNicknameNoun
import model.AccountNicknameNounTableImpl
import neton.database.api.Table

object AccountNicknameNounTable : Table<AccountNicknameNoun, Long> by AccountNicknameNounTableImpl
