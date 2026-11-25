/*
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */
package com.android.bluetooth.bthelper.adapter.slices

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.TypedArray
import android.net.Uri
import android.util.Log
import androidx.core.graphics.drawable.IconCompat
import androidx.slice.Slice
import androidx.slice.SliceProvider
import androidx.slice.builders.ListBuilder
import androidx.slice.builders.SliceAction
import com.android.bluetooth.bthelper.adapter.Constants
import com.android.bluetooth.bthelper.adapter.R

class BtHelperSliceProvider : SliceProvider() {

    private var mContext: Context? = null

    override fun onCreateSliceProvider(): Boolean {
        try {
            mContext = context
        } catch (e: NullPointerException) {
            Log.e(TAG, "Error in onCreateSliceProvider", e)
        }
        return true
    }

    override fun onBindSlice(sliceUri: Uri): Slice? {
        if (mContext == null) {
            return null
        }
        val authority: String? = sliceUri.authority
        if (authority != Constants.AUTHORITY_SLICE) {
            return null
        }
        val path: String? = sliceUri.path
        when (path) {
            Constants.SLICE_BTHELPER -> return createBtHelperSlice(sliceUri)
        }
        return null
    }

    private fun createBtHelperSlice(sliceUri: Uri): Slice? {
        val context = mContext

        if (context == null) return null

        val title = context.getString(R.string.more_settings_title)
        val subtitle = context.getString(R.string.more_settings_subtitle)
        // Settings resolves the explicit activity from the PendingIntent for two-pane layouts.
        val intent = Intent(Intent.ACTION_MAIN).setClassName(
            Constants.AUTHORITY_BTHELPER,
            Constants.ACTIVITY_BTHELPER_MAIN_SETTINGS,
        )
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val icon = IconCompat.createWithResource(context, R.drawable.ic_chevron_right)
        val action = SliceAction(pendingIntent, icon.toIcon(), title)

        val listBuilder = ListBuilder(context, sliceUri, INFINITY)
        listBuilder.addRow(
            ListBuilder.RowBuilder(sliceUri)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setPrimaryAction(action)
        )

        listBuilder.setAccentColor(getColorAccentDefaultColor(context))
        return listBuilder.build()
    }

    companion object {
        const val TAG: String = "BtHelperSliceProvider"

        /** Constant representing infinity. */
        private const val INFINITY: Long = -1

        private fun getColorAccentDefaultColor(context: Context): Int {
            return getColorAttrDefaultColor(context, android.R.attr.colorAccent, 0)
        }

        private fun getColorAttrDefaultColor(context: Context, attr: Int, defValue: Int): Int {
            val ta: TypedArray = context.obtainStyledAttributes(intArrayOf(attr))
            val colorAccent: Int = ta.getColor(0, defValue)
            ta.recycle()
            return colorAccent
        }
    }
}
