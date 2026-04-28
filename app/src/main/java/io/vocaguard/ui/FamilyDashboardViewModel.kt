package io.vocaguard.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.vocaguard.data.ScamType
import io.vocaguard.data.db.FamilyAlertDao
import io.vocaguard.data.db.FamilyAlertEntity
import io.vocaguard.data.db.VocaGuardDatabase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FamilyDashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application.applicationContext
    private val dao: FamilyAlertDao = VocaGuardDatabase.getInstance(context).familyAlertDao()

    /** Live stream of all received family alerts, newest first. */
    val alerts: StateFlow<List<FamilyAlertEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Live count of unread alerts — drives the badge on the Family tab. */
    val unreadCount: StateFlow<Int> = dao.observeUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun markRead(id: Long) = viewModelScope.launch { dao.markRead(id) }
    fun markAllRead() = viewModelScope.launch { dao.markAllRead() }
    fun delete(id: Long) = viewModelScope.launch { dao.delete(id) }
    fun clearAll() = viewModelScope.launch { dao.clearAll() }

    /** Stores a new alert received from the vocaguard://alert deep-link. */
    fun addAlertFromDeepLink(
        senderName: String,
        scamType: ScamType,
        confidence: Float,
        timestamp: Long
    ) = viewModelScope.launch {
        dao.insert(
            FamilyAlertEntity(
                senderName = senderName,
                senderNumber = "",       // deep-link doesn't carry a number to protect privacy
                scamType = scamType.name,
                confidence = confidence,
                timestamp = timestamp,
                isRead = false
            )
        )
    }
}
