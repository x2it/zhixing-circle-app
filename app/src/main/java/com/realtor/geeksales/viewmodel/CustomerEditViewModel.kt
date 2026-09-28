package com.realtor.geeksales.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.realtor.geeksales.data.db.Customer
import com.realtor.geeksales.data.db.IntentLevel
import com.realtor.geeksales.data.repo.CustomerRepository
import com.realtor.geeksales.data.schema.FieldDef
import com.realtor.geeksales.data.schema.SchemaStore
import com.realtor.geeksales.util.Formatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditFormState(
    val id: Long = 0L,
    val name: String = "",
    val phone: String = "",
    val phone2: String = "",
    val gender: String = "",
    val age: String = "",
    val wechat: String = "",
    val source: String = "",
    val areaPref: String = "",
    val budgetMin: String = "",
    val budgetMax: String = "",
    val houseType: String = "",
    val targetProject: String = "",
    val intentLevel: IntentLevel = IntentLevel.U,
    val note: String = "",
    val nextFollowAt: Long? = null,
    val tags: String = "",
    // 通讯录对齐字段
    val email: String = "",
    val company: String = "",
    val jobTitle: String = "",
    val address: String = "",
    val nickname: String = "",
    val website: String = "",
    val birthday: String = "",
    val im: String = "",
    // 扩展字段（线上模板自定义字段，key→value 存 customer_fields）
    val extFields: Map<String, String> = emptyMap()
)

@HiltViewModel
class CustomerEditViewModel @Inject constructor(
    private val repo: CustomerRepository,
    private val schemaStore: SchemaStore
) : ViewModel() {

    private val _form = MutableStateFlow(EditFormState())
    val form: StateFlow<EditFormState> = _form

    /** 当前生效模板字段（内置 + 扩展），供表单动态渲染 */
    private val _schema = MutableStateFlow(schemaStore.current())
    val schema: StateFlow<List<FieldDef>> = _schema

    /** 模板元数据（分层 tiers / 身份标签 / 属性标签），驱动等级 chips 与标签选择器 */
    private val _templateMeta = MutableStateFlow(schemaStore.meta())
    val templateMeta: StateFlow<com.realtor.geeksales.data.schema.TemplateMeta> = _templateMeta

    val savedOk = MutableStateFlow(false)

    private var loadedId = -1L

    fun initFor(id: Long) {
        if (id == 0L) {
            _form.value = EditFormState()
            return
        }
        if (id == loadedId) return
        loadIfExists(id)
    }

    private fun loadIfExists(id: Long) = viewModelScope.launch {
        if (id == 0L) return@launch
        val c = repo.getById(id) ?: return@launch
        loadedId = id
        // 回显已有标签，避免编辑保存时 unlinkAll 误删原标签（数据丢失）
        val existingTags = repo.tagsOf(id).joinToString(", ")
        val ext = repo.extFieldsOf(id)
        _form.value = EditFormState(
            id = c.id,
            name = c.name,
            phone = c.phone,
            phone2 = c.phone2 ?: "",
            gender = c.gender ?: "",
            age = c.age?.toString() ?: "",
            wechat = c.wechat ?: "",
            source = c.source ?: "",
            areaPref = c.areaPref ?: "",
            budgetMin = c.budgetMinWan?.toString() ?: "",
            budgetMax = c.budgetMaxWan?.toString() ?: "",
            houseType = c.houseType ?: "",
            targetProject = c.targetProject ?: "",
            intentLevel = c.intentLevel,
            note = c.note ?: "",
            nextFollowAt = c.nextFollowAt,
            tags = existingTags,
            email = c.email ?: "",
            company = c.company ?: "",
            jobTitle = c.jobTitle ?: "",
            address = c.address ?: "",
            nickname = c.nickname ?: "",
            website = c.website ?: "",
            birthday = c.birthday ?: "",
            im = c.im ?: "",
            extFields = ext
        )
    }

    fun update(fn: (EditFormState) -> EditFormState) {
        _form.value = fn(_form.value)
    }

    /** 更新单个扩展字段值（动态表单） */
    fun updateExt(key: String, value: String) {
        _form.value = _form.value.copy(extFields = _form.value.extFields + (key to value))
    }

    fun save() = viewModelScope.launch {
        val f = _form.value
        if (f.name.isBlank() || !Formatter.isValidCnPhone(f.phone)) return@launch
        val tags = f.tags.split(',', '，', ';', '|').map { it.trim() }.filter { it.isNotBlank() }
        val id = repo.upsert(
            Customer(
                id = f.id,
                name = f.name.trim(),
                phone = f.phone.trim(),
                phoneNormalized = Formatter.normalizePhone(f.phone),
                phone2 = f.phone2.takeIf { it.isNotBlank() },
                gender = f.gender.takeIf { it.isNotBlank() },
                age = f.age.toIntOrNull(),
                wechat = f.wechat.takeIf { it.isNotBlank() },
                source = f.source.takeIf { it.isNotBlank() },
                areaPref = f.areaPref.takeIf { it.isNotBlank() },
                budgetMinWan = f.budgetMin.toIntOrNull(),
                budgetMaxWan = f.budgetMax.toIntOrNull(),
                houseType = f.houseType.takeIf { it.isNotBlank() },
                targetProject = f.targetProject.takeIf { it.isNotBlank() },
                intentLevel = f.intentLevel,
                note = f.note.takeIf { it.isNotBlank() },
                nextFollowAt = f.nextFollowAt,
                email = f.email.takeIf { it.isNotBlank() },
                company = f.company.takeIf { it.isNotBlank() },
                jobTitle = f.jobTitle.takeIf { it.isNotBlank() },
                address = f.address.takeIf { it.isNotBlank() },
                nickname = f.nickname.takeIf { it.isNotBlank() },
                website = f.website.takeIf { it.isNotBlank() },
                birthday = f.birthday.takeIf { it.isNotBlank() },
                im = f.im.takeIf { it.isNotBlank() }
            ),
            tags.takeIf { it.isNotEmpty() }
        )
        if (id > 0L) {
            // 写扩展字段（非空值入库；空值删除保持干净）
            repo.putExtFields(id, f.extFields.filterValues { it.isNotBlank() })
            f.extFields.filterValues { it.isBlank() }.keys.forEach { k -> repo.deleteExtField(id, k) }
            savedOk.value = true
        }
    }
}
