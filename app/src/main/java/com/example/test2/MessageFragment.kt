package com.example.test2

import java.nio.ByteBuffer
import java.util.*

// Модель для отображения в UI
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val senderId: String,
    val hops: Int = 0 // для отображения количества прыжков
)

data class MessageFragment(
    val messageId: String,
    val fragmentIndex: Int, // номер фрагмента (0, 1, 2, ...)
    val totalFragments: Int, // общее количество фрагментов
    val fragmentData: ByteArray, // данные фрагмента
    val originalSenderId: String,
    val ttl: Int = 5,
    val hops: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
) {
    
    // Компактный формат для BLE (20 байт максимум)
    fun toCompactBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(20)
        
        // 4 байта: hash от messageId (стабильный)
        val messageIdHash = messageId.hashCode()
        buffer.putInt(messageIdHash)
        
        // 1 байт: fragmentIndex (max 255 фрагментов)
        buffer.put(fragmentIndex.toByte())
        
        // 1 байт: totalFragments (max 255 фрагментов) 
        buffer.put(totalFragments.toByte())
        
        // 1 байт: TTL (4 бита) + hops (4 бита)
        val ttlAndHops = ((ttl and 0x0F) shl 4) or (hops and 0x0F)
        buffer.put(ttlAndHops.toByte())
        
        // 2 байта: hash от senderId
        val senderIdHash = originalSenderId.hashCode()
        buffer.putShort(senderIdHash.toShort())
        
        // Остальные байты: данные фрагмента (максимум 11 байт)
        val maxDataLength = 11
        val actualDataLength = minOf(fragmentData.size, maxDataLength)
        buffer.put(fragmentData, 0, actualDataLength)
        
        // Возвращаем только используемые байты
        val result = ByteArray(9 + actualDataLength)
        buffer.rewind()
        buffer.get(result)
        return result
    }
    
    fun decrementTtl(): MessageFragment {
        return this.copy(ttl = ttl - 1, hops = hops + 1)
    }
    
    fun isExpired(): Boolean {
        return ttl <= 0
    }
    
    companion object {
        fun fromCompactBytes(data: ByteArray): MessageFragment? {
            return try {
                if (data.size < 9) return null
                
                val buffer = ByteBuffer.wrap(data)
                
                // Читаем messageId hash
                val messageIdHash = buffer.int
                val messageId = "msg_$messageIdHash"
                
                // Читаем fragment info
                val fragmentIndex = buffer.get().toInt() and 0xFF
                val totalFragments = buffer.get().toInt() and 0xFF
                
                // Читаем TTL и hops
                val ttlAndHops = buffer.get().toInt() and 0xFF
                val ttl = (ttlAndHops shr 4) and 0x0F
                val hops = ttlAndHops and 0x0F
                
                // Читаем senderId hash
                val senderIdHash = buffer.short.toInt()
                val senderId = "Device_$senderIdHash"
                
                // Читаем данные фрагмента
                val dataLength = data.size - 9
                val fragmentData = ByteArray(dataLength)
                buffer.get(fragmentData)
                
                MessageFragment(
                    messageId = messageId,
                    fragmentIndex = fragmentIndex,
                    totalFragments = totalFragments,
                    fragmentData = fragmentData,
                    originalSenderId = senderId,
                    ttl = ttl,
                    hops = hops,
                    timestamp = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                null
            }
        }
        
        // Разбивает длинное сообщение на фрагменты
        fun fragmentMessage(text: String, senderId: String): List<MessageFragment> {
            val messageId = UUID.randomUUID().toString()
            val textBytes = text.toByteArray(Charsets.UTF_8)
            val fragmentSize = 11 // максимум данных в одном фрагменте
            
            val fragments = mutableListOf<MessageFragment>()
            val totalFragments = (textBytes.size + fragmentSize - 1) / fragmentSize // округление вверх
            
            for (i in 0 until totalFragments) {
                val startIndex = i * fragmentSize
                val endIndex = minOf(startIndex + fragmentSize, textBytes.size)
                val fragmentData = textBytes.sliceArray(startIndex until endIndex)
                
                fragments.add(
                    MessageFragment(
                        messageId = messageId,
                        fragmentIndex = i,
                        totalFragments = totalFragments,
                        fragmentData = fragmentData,
                        originalSenderId = senderId
                    )
                )
            }
            
            return fragments
        }
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as MessageFragment
        
        if (messageId != other.messageId) return false
        if (fragmentIndex != other.fragmentIndex) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + fragmentIndex
        return result
    }
}

// Класс для сборки фрагментов в полные сообщения
class FragmentAssembler {
    private val incompleteMessages = mutableMapOf<String, MutableMap<Int, MessageFragment>>()
    private val MESSAGE_TIMEOUT = 30000L // 30 секунд на сборку сообщения
    
    fun addFragment(fragment: MessageFragment): Message? {
        val messageId = fragment.messageId
        
        // Получаем или создаем контейнер для фрагментов этого сообщения
        val messageFragments = incompleteMessages.getOrPut(messageId) { mutableMapOf() }
        
        // Добавляем фрагмент
        messageFragments[fragment.fragmentIndex] = fragment
        
        // Проверяем, собрали ли мы все фрагменты
        if (messageFragments.size == fragment.totalFragments) {
            val completeMessage = assembleMessage(messageFragments.values.sortedBy { it.fragmentIndex })
            incompleteMessages.remove(messageId)
            return completeMessage
        }
        
        return null // сообщение еще не полное
    }
    
    private fun assembleMessage(fragments: List<MessageFragment>): Message {
        val textBuilder = StringBuilder()
        
        fragments.forEach { fragment ->
            textBuilder.append(String(fragment.fragmentData, Charsets.UTF_8))
        }
        
        val firstFragment = fragments.first()
        return Message(
            id = firstFragment.messageId,
            text = textBuilder.toString(),
            timestamp = firstFragment.timestamp,
            senderId = firstFragment.originalSenderId,
            hops = firstFragment.hops
        )
    }
    
    // Очистка устаревших неполных сообщений
    fun cleanup() {
        val currentTime = System.currentTimeMillis()
        incompleteMessages.entries.removeAll { (_, fragments) ->
            fragments.values.any { currentTime - it.timestamp > MESSAGE_TIMEOUT }
        }
    }
} 