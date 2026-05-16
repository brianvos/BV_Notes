package com.bv.notes.ui.settings

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.bv.notes.databinding.FragmentSettingsBinding
import com.bv.notes.util.ThemeManager

class SettingsFragment : Fragment() {
    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.btnBack.setOnClickListener { findNavController().navigateUp() }

        val isDark = (requireContext().resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        b.switchDarkMode.isChecked = isDark

        b.optionDarkMode.setOnClickListener {
            val newMode = if (b.switchDarkMode.isChecked) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
            ThemeManager.setNightMode(requireContext(), newMode)
            b.switchDarkMode.isChecked = !b.switchDarkMode.isChecked
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
