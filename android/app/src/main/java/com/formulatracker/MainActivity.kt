package com.formulatracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.formulatracker.ui.ScheduleScreen
import com.formulatracker.ui.theme.FormulaTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FormulaTrackerTheme {
                ScheduleScreen()
            }
        }
    }
}
