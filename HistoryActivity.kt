package com.example.kmtrackerpro

// ─────────────────────────────────────────────────────────────
//  HistoryActivity.kt
//
//  Displays all past runs in a RecyclerView.
//  Each row shows: date, distance, duration, average speed.
//  Long-press a row to delete that run.
// ─────────────────────────────────────────────────────────────

import android.os.Bundle
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.kmtrackerpro.databinding.ActivityHistoryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var db: AppDatabase
    private lateinit var adapter: RunAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "Run History"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        db = AppDatabase.getDatabase(this)
        adapter = RunAdapter { run -> confirmDelete(run) }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        // LiveData automatically refreshes the list whenever the database changes
        db.runDao().getAllRuns().observe(this) { runs ->
            adapter.submitList(runs)
            // Show empty-state message if no runs exist
            binding.tvEmpty.visibility =
                if (runs.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // Ask the user to confirm before deleting a run
    private fun confirmDelete(run: RunEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete Run")
            .setMessage("Delete the run on ${run.date}?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.runDao().deleteRun(run.id)
                }
                Toast.makeText(this, "Run deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

// ─────────────────────────────────────────────────────────────
//  RecyclerView Adapter  –  one row per saved run
// ─────────────────────────────────────────────────────────────

class RunAdapter(
    private val onLongPress: (RunEntity) -> Unit
) : RecyclerView.Adapter<RunAdapter.RunViewHolder>() {

    private var runs = listOf<RunEntity>()

    fun submitList(newRuns: List<RunEntity>) {
        runs = newRuns
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RunViewHolder {
        // Inflate our run_item layout for each row
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_run, parent, false)
        return RunViewHolder(view)
    }

    override fun onBindViewHolder(holder: RunViewHolder, position: Int) {
        holder.bind(runs[position], onLongPress)
    }

    override fun getItemCount() = runs.size

    // ── ViewHolder ──
    class RunViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val tvDate:     TextView = itemView.findViewById(R.id.tvDate)
        private val tvDistance: TextView = itemView.findViewById(R.id.tvItemDistance)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvItemDuration)
        private val tvSpeed:    TextView = itemView.findViewById(R.id.tvItemSpeed)

        fun bind(run: RunEntity, onLongPress: (RunEntity) -> Unit) {
            tvDate.text     = run.date
            tvDistance.text = "%.2f km".format(run.distanceKm)
            tvDuration.text = formatTime(run.durationSeconds)
            tvSpeed.text    = "Avg %.1f km/h".format(run.avgSpeedKmh)

            // Long-press triggers delete confirmation
            itemView.setOnLongClickListener {
                onLongPress(run)
                true
            }
        }

        private fun formatTime(seconds: Long): String {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return if (h > 0) "%dh %02dm %02ds".format(h, m, s)
            else             "%02dm %02ds".format(m, s)
        }
    }
}
