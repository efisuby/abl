package com.efisuby.abl.ui.book

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.LinearLayoutManager
import com.efisuby.abl.appContainer
import com.efisuby.abl.databinding.ActivityBookBinding
import com.efisuby.abl.ui.common.applySystemBarInsets
import com.efisuby.abl.ui.player.PlayerActivity
import kotlinx.coroutines.launch

/**
 * Cover, metadata and chapter list of one book; "listen" or a chapter tap opens the player.
 * A minimal version: bookmarks and the rest of task 4.2 are still to come.
 */
class BookActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookBinding

    private val viewModel: BookViewModel by viewModels {
        val bookId = requireNotNull(intent.getStringExtra(EXTRA_BOOK_ID)) { "BookActivity needs a book id" }
        viewModelFactory { initializer { BookViewModel(appContainer.libraryRepository, bookId) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityBookBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.list)

        val bookId = requireNotNull(intent.getStringExtra(EXTRA_BOOK_ID))
        val adapter = BookAdapter(lifecycleScope) { chapter ->
            startActivity(PlayerActivity.intent(this, bookId, chapter?.id))
        }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(adapter::submit)
            }
        }
    }

    companion object {
        private const val EXTRA_BOOK_ID = "bookId"

        fun intent(context: Context, bookId: String): Intent =
            Intent(context, BookActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
    }
}
