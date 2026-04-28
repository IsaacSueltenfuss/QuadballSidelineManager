package com.example.quadballsidelinemanager.features.roster
import android.content.res.ColorStateList
import android.graphics.Rect
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.example.quadballsidelinemanager.R
import com.example.quadballsidelinemanager.databinding.PlayerCardBinding
import com.example.quadballsidelinemanager.models.Player
import com.example.quadballsidelinemanager.models.QuadballPosition
import com.example.quadballsidelinemanager.SortType
import com.example.quadballsidelinemanager.utils.Storage

class RosterAdapter(private val onPlayerClick: (Player) -> Unit,
                    private val onPlayerLongClick: (View, Player) -> Boolean) :
    ListAdapter<Player, RosterAdapter.PlayerViewHolder>(PlayerDiffCallback()) {
    var displayMode: SortType = SortType.POSSESSIONS

    class PlayerViewHolder(val binding: PlayerCardBinding) : RecyclerView.ViewHolder(binding.root)

    // Inflates the original view for the recycler view
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlayerViewHolder {
        val binding = PlayerCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PlayerViewHolder(binding)
    }

    // On rebind of cards, recycle
    override fun onBindViewHolder(holder: PlayerViewHolder, position: Int) {
        val player = getItem(position)
        val context = holder.itemView.context

        Log.d("ADAPTER_DEBUG", "Binding player: ${player.lastName} | LongClick null? ${onPlayerLongClick == null}")

        // Updates the player card based on whether they are active, adds listeners, loads image, updates statbar
        holder.binding.apply {
            root.alpha = if (player.isActive) 1.0f else 0.4f
            playerPhoto.alpha = if (player.isActive) 1.0f else 0.4f

            playerName.text = player.name.uppercase()
            playerNumber.text = "#${player.number}"
            positionBadgeText.text = player.primaryPosition.toString().uppercase()

            if (player.isActive) {
                holder.binding.statBar.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.burnt_orange))
                holder.binding.statBar.setBackgroundColor(ContextCompat.getColor(holder.itemView.context, R.color.white))
                holder.binding.playerCardContainer.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(holder.itemView.context, R.color.white)))
            } else {
                holder.binding.statBar.text = "INACTIVE"
                holder.binding.statBar.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.black))
                holder.binding.statBar.setBackgroundColor(ContextCompat.getColor(holder.itemView.context, R.color.red))
                holder.binding.playerCardContainer.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(holder.itemView.context, R.color.red)))
            }

            root.setOnClickListener { onPlayerClick(player) }
            root.setOnLongClickListener { view ->
                onPlayerLongClick(view, player)
                true
            }

            Glide.with(this.root).clear(this.playerPhoto)
            this.playerPhoto.setImageResource(R.drawable.ic_player_placeholder)

            Log.d("PHOTO_DEBUG", "Player: ${player.name} | ID: ${player.photoResId}")

            val storage = Storage()
            storage.getPlayerHeadshot(player.id).addOnSuccessListener { uri ->
                Glide.with(this.root)
                    .load(uri)
                    .placeholder(R.drawable.ic_player_placeholder)
                    .centerCrop()
                    .into(this.playerPhoto)
            }.addOnFailureListener {
                // Both extensions failed, Glide will stay on the placeholder
                Log.e("STORAGE", "No headshot found for ${player.id} (.jpg or .jpeg)")
            }

            holder.binding.statBar.text = when(displayMode) {
                SortType.POSSESSIONS -> player.totalPossessions.toString() + " POSSESSIONS"
                SortType.POSITION -> player.primaryPosition.toString().uppercase()
                SortType.PLUS_MINUS -> player.plusMinus.toString() + " PLUS/MINUS"
                SortType.GENDER -> player.gender.toString().uppercase()
                SortType.NUMBER -> "JERSEY NUMBER #" + player.number.toString()
                else -> player.primaryPosition.toString().uppercase()
            }

            val flagColor = when (player.primaryPosition) {
                QuadballPosition.KEEPER -> R.color.green // Green
                QuadballPosition.CHASER -> R.color.white // White
                QuadballPosition.BEATER -> R.color.black // Black
                QuadballPosition.SEEKER -> R.color.yellow // Yellow
                else -> R.color.red // Red
            }

            val textColor = when (player.primaryPosition) {
                QuadballPosition.BEATER -> R.color.white // White
                else -> R.color.black // Black
            }

            positionFlag.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(context, flagColor))
            positionBadgeText.setTextColor(ContextCompat.getColor(context, textColor))
            positionBadgeText.text = player.primaryPosition.toString().uppercase()
        }
    }
}

class GridSpacingItemDecoration(private val spanCount: Int, private val spacing: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        val column = position % spanCount

        outRect.left = spacing - column * spacing / spanCount
        outRect.right = (column + 1) * spacing / spanCount

        if (position < spanCount) outRect.top = spacing
        outRect.bottom = spacing
    }
}

class PlayerDiffCallback : DiffUtil.ItemCallback<Player>() {
    override fun areItemsTheSame(oldItem: Player, newItem: Player): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: Player, newItem: Player): Boolean {
        return oldItem == newItem
    }
}