package com.fallgist.nishinomiyalibrary.data.repository

import com.fallgist.nishinomiyalibrary.data.local.AppDatabase
import com.fallgist.nishinomiyalibrary.data.local.CredentialStore
import com.fallgist.nishinomiyalibrary.data.local.dao.MemberDao
import com.fallgist.nishinomiyalibrary.data.local.entity.MemberEntity
import com.fallgist.nishinomiyalibrary.domain.model.Member
import com.fallgist.nishinomiyalibrary.domain.repository.FamilyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FamilyRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val memberDao: MemberDao,
    private val credentialStore: CredentialStore,
) : FamilyRepository {
    override fun members(): Flow<List<Member>> = memberDao.observeAll().map { members ->
        members.map(MemberEntity::toDomain)
    }

    override suspend fun addMember(name: String, colorHex: String, cardNumber: String, password: String) {
        val memberId = database.insertMemberAtEnd(
            MemberEntity(
                name = name,
                colorHex = colorHex,
                cardNumber = cardNumber,
                sortOrder = 0,
            ),
        )
        try {
            credentialStore.savePassword(memberId, password)
        } catch (_: Exception) {
            runCatching {
                database.memberDao().getById(memberId)?.let { database.deleteMemberAndLocalData(it) }
            }
            throw IllegalStateException("資格情報を保存できませんでした")
        }
    }

    override suspend fun updateMember(member: Member, newPassword: String?) {
        if (newPassword != null) {
            try {
                credentialStore.savePassword(member.id, newPassword)
            } catch (_: Exception) {
                throw IllegalStateException("資格情報を保存できませんでした")
            }
        }
        memberDao.update(member.toEntity())
    }

    override suspend fun removeMember(memberId: Long) {
        val member = memberDao.getById(memberId) ?: return
        try {
            credentialStore.delete(memberId)
        } catch (_: Exception) {
            throw IllegalStateException("資格情報を削除できませんでした")
        }
        database.deleteMemberAndLocalData(member)
    }
}
