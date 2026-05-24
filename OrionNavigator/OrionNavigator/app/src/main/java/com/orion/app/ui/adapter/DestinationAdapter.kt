package com.orion.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.orion.app.R
import com.orion.app.data.BeaconInfo

/**
 * Adapter for destination selection RecyclerView
 * Displays available beacon locations as selectable cards
 */
class DestinationAdapter(
    private val destinations: List<BeaconInfo>,
    private val onItemClick: (BeaconInfo) -> Unit
) : RecyclerView.Adapter<DestinationAdapter.ViewHolder>() {

    private var selectedPosition: Int = RecyclerView.NO_POSITION

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card: MaterialCardView = itemView as MaterialCardView
        val tvLocationName: TextView = itemView.findViewById(R.id.tvLocationName)
        val tvLocationInfo: TextView = itemView.findViewById(R.id.tvLocationInfo)
        val ivSelected: ImageView = itemView.findViewById(R.id.ivSelected)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_destination, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val destination = destinations[position]
        
        holder.tvLocationName.text = destination.location
        holder.tvLocationInfo.text = destination.description.ifEmpty { 
            "Minor: ${destination.minor}" 
        }

        val isSelected = position == selectedPosition
        holder.ivSelected.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
        
        // Update card appearance for selection
        val strokeColor = if (isSelected) {
            ContextCompat.getColor(holder.itemView.context, R.color.accent)
        } else {
            ContextCompat.getColor(holder.itemView.context, android.R.color.transparent)
        }
        holder.card.strokeColor = strokeColor
        holder.card.strokeWidth = if (isSelected) 4 else 0

        // Accessibility
        holder.card.contentDescription = buildString {
            append(destination.location)
            if (destination.description.isNotEmpty()) {
                append(", ")
                append(destination.description)
            }
            if (isSelected) {
                append(", dipilih")
            }
        }

        holder.card.setOnClickListener {
            val previousPosition = selectedPosition
            selectedPosition = holder.adapterPosition
            
            notifyItemChanged(previousPosition)
            notifyItemChanged(selectedPosition)
            
            onItemClick(destination)
        }
    }

    override fun getItemCount(): Int = destinations.size

    fun setSelectedBeacon(beacon: BeaconInfo) {
        val newPosition = destinations.indexOfFirst { 
            it.uuid == beacon.uuid && 
            it.major == beacon.major && 
            it.minor == beacon.minor 
        }
        if (newPosition != selectedPosition) {
            val previousPosition = selectedPosition
            selectedPosition = newPosition
            notifyItemChanged(previousPosition)
            notifyItemChanged(selectedPosition)
        }
    }
}
