package com.nemoclaw.chat

import androidx.lifecycle.ViewModel

/**
 * Lo stato chat sopravvive alla rotazione (il ViewModel e retained,
 * il remember no). Niente logica qui dentro: solo ownership del holder.
 * Process death resta coperto dagli snapshot su disco come prima.
 */
internal class ChatViewModel : ViewModel() {
    val chatState = ChatStateHolder()
}
