package com.veilframe.app.databinding

import androidx.core.widget.NestedScrollView
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.GridLayout
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.progressindicator.LinearProgressIndicator

// =============================================================================
// Home Dashboard Views (layoutHomeDashboard -> layout_home_dashboard.xml)
// =============================================================================

inline val ActivityMainBinding.scrollHome: NestedScrollView
    get() = layoutHomeDashboard.scrollHome

inline val ActivityMainBinding.cardHomeFileIntake: MaterialCardView
    get() = layoutHomeDashboard.cardHomeFileIntake

inline val ActivityMainBinding.layoutHomeIntakePickers: LinearLayout
    get() = layoutHomeDashboard.layoutHomeIntakePickers

inline val ActivityMainBinding.btnHomeIntakePhoto: MaterialButton
    get() = layoutHomeDashboard.btnHomeIntakePhoto

inline val ActivityMainBinding.btnHomeIntakeVideo: MaterialButton
    get() = layoutHomeDashboard.btnHomeIntakeVideo

inline val ActivityMainBinding.btnHomeIntakeDoc: MaterialButton
    get() = layoutHomeDashboard.btnHomeIntakeDoc

inline val ActivityMainBinding.btnHomeIntakeBrowse: MaterialButton
    get() = layoutHomeDashboard.btnHomeIntakeBrowse

inline val ActivityMainBinding.layoutHomeIntakeMounted: LinearLayout
    get() = layoutHomeDashboard.layoutHomeIntakeMounted

inline val ActivityMainBinding.ivHomeIntakeIcon: ImageView
    get() = layoutHomeDashboard.ivHomeIntakeIcon

inline val ActivityMainBinding.tvHomeIntakeFileName: TextView
    get() = layoutHomeDashboard.tvHomeIntakeFileName

inline val ActivityMainBinding.tvHomeIntakeMeta: TextView
    get() = layoutHomeDashboard.tvHomeIntakeMeta

inline val ActivityMainBinding.btnHomeIntakeClear: MaterialButton
    get() = layoutHomeDashboard.btnHomeIntakeClear

inline val ActivityMainBinding.layoutHomeSuggestedEngines: LinearLayout
    get() = layoutHomeDashboard.layoutHomeSuggestedEngines

inline val ActivityMainBinding.btnSuggestEngine1: MaterialButton
    get() = layoutHomeDashboard.btnSuggestEngine1

inline val ActivityMainBinding.btnSuggestEngine2: MaterialButton
    get() = layoutHomeDashboard.btnSuggestEngine2

inline val ActivityMainBinding.btnSuggestEngine3: MaterialButton
    get() = layoutHomeDashboard.btnSuggestEngine3

inline val ActivityMainBinding.btnSeeAllTools: MaterialButton
    get() = layoutHomeDashboard.btnSeeAllTools

inline val ActivityMainBinding.gridHomeRecent: GridLayout
    get() = layoutHomeDashboard.gridHomeRecent

inline val ActivityMainBinding.cardRecent1: MaterialCardView
    get() = layoutHomeDashboard.cardRecent1

inline val ActivityMainBinding.cardRecent2: MaterialCardView
    get() = layoutHomeDashboard.cardRecent2

inline val ActivityMainBinding.cardRecent3: MaterialCardView
    get() = layoutHomeDashboard.cardRecent3

inline val ActivityMainBinding.cardRecent4: MaterialCardView
    get() = layoutHomeDashboard.cardRecent4

inline val ActivityMainBinding.cardPrivacyPulse: MaterialCardView
    get() = layoutHomeDashboard.cardPrivacyPulse

// =============================================================================
// Dedicated Tool Workflow Views (layoutToolWorkflow -> layout_tool_workflow.xml)
// =============================================================================

inline val ActivityMainBinding.scrollTool: NestedScrollView
    get() = layoutToolWorkflow.scrollTool

inline val ActivityMainBinding.cardTargetSelection: MaterialCardView
    get() = layoutToolWorkflow.cardTargetSelection

inline val ActivityMainBinding.cardToolHeader: LinearLayout
    get() = layoutToolWorkflow.cardToolHeader

inline val ActivityMainBinding.tvToolCardHeaderTitle: TextView
    get() = layoutToolWorkflow.tvToolCardHeaderTitle

inline val ActivityMainBinding.layoutTargetEmpty: LinearLayout
    get() = layoutToolWorkflow.layoutTargetEmpty

inline val ActivityMainBinding.btnPickFolder: MaterialButton
    get() = layoutToolWorkflow.btnPickFolder

inline val ActivityMainBinding.btnPickFile: MaterialButton
    get() = layoutToolWorkflow.btnPickFile

inline val ActivityMainBinding.layoutTargetMounted: LinearLayout
    get() = layoutToolWorkflow.layoutTargetMounted

inline val ActivityMainBinding.tvSelectedPath: TextView
    get() = layoutToolWorkflow.tvSelectedPath

inline val ActivityMainBinding.tvTargetDetails: TextView
    get() = layoutToolWorkflow.tvTargetDetails

inline val ActivityMainBinding.btnChangeTarget: MaterialButton
    get() = layoutToolWorkflow.btnChangeTarget

inline val ActivityMainBinding.btnClearTarget: MaterialButton
    get() = layoutToolWorkflow.btnClearTarget

inline val ActivityMainBinding.cardConfiguration: MaterialCardView
    get() = layoutToolWorkflow.cardConfiguration

inline val ActivityMainBinding.tvParamHeader: TextView
    get() = layoutToolWorkflow.tvParamHeader

inline val ActivityMainBinding.layoutToolSlider: LinearLayout
    get() = layoutToolWorkflow.layoutToolSlider

inline val ActivityMainBinding.tvToolSliderTitle: TextView
    get() = layoutToolWorkflow.tvToolSliderTitle

inline val ActivityMainBinding.tvToolSliderValue: TextView
    get() = layoutToolWorkflow.tvToolSliderValue

inline val ActivityMainBinding.sliderToolIntensity: Slider
    get() = layoutToolWorkflow.sliderToolIntensity

inline val ActivityMainBinding.tvPrimaryOptionTitle: TextView
    get() = layoutToolWorkflow.tvPrimaryOptionTitle

inline val ActivityMainBinding.tvPrimaryOptionDesc: TextView
    get() = layoutToolWorkflow.tvPrimaryOptionDesc

inline val ActivityMainBinding.chipGroupPrimaryOptions: ChipGroup
    get() = layoutToolWorkflow.chipGroupPrimaryOptions

inline val ActivityMainBinding.tvFormatOptionTitle: TextView
    get() = layoutToolWorkflow.tvFormatOptionTitle

inline val ActivityMainBinding.tvFormatOptionDesc: TextView
    get() = layoutToolWorkflow.tvFormatOptionDesc

inline val ActivityMainBinding.chipGroupFormat: ChipGroup
    get() = layoutToolWorkflow.chipGroupFormat

inline val ActivityMainBinding.tvSwitch1Title: TextView
    get() = layoutToolWorkflow.tvSwitch1Title

inline val ActivityMainBinding.tvSwitch1Desc: TextView
    get() = layoutToolWorkflow.tvSwitch1Desc

inline val ActivityMainBinding.switchOption1: MaterialSwitch
    get() = layoutToolWorkflow.switchOption1

inline val ActivityMainBinding.tvSwitch2Title: TextView
    get() = layoutToolWorkflow.tvSwitch2Title

inline val ActivityMainBinding.tvSwitch2Desc: TextView
    get() = layoutToolWorkflow.tvSwitch2Desc

inline val ActivityMainBinding.switchOption2: MaterialSwitch
    get() = layoutToolWorkflow.switchOption2

inline val ActivityMainBinding.tvSwitch3Title: TextView
    get() = layoutToolWorkflow.tvSwitch3Title

inline val ActivityMainBinding.tvSwitch3Desc: TextView
    get() = layoutToolWorkflow.tvSwitch3Desc

inline val ActivityMainBinding.switchOption3: MaterialSwitch
    get() = layoutToolWorkflow.switchOption3

inline val ActivityMainBinding.layoutBundleDepthControl: LinearLayout
    get() = layoutToolWorkflow.layoutBundleDepthControl

inline val ActivityMainBinding.tvBundleDepthTitle: TextView
    get() = layoutToolWorkflow.tvBundleDepthTitle

inline val ActivityMainBinding.tvBundleDepthDesc: TextView
    get() = layoutToolWorkflow.tvBundleDepthDesc

inline val ActivityMainBinding.switchBundleFullDepth: MaterialSwitch
    get() = layoutToolWorkflow.switchBundleFullDepth

inline val ActivityMainBinding.layoutBundleCustomDepthRow: LinearLayout
    get() = layoutToolWorkflow.layoutBundleCustomDepthRow

inline val ActivityMainBinding.btnBundleDepthDecrement: MaterialButton
    get() = layoutToolWorkflow.btnBundleDepthDecrement

inline val ActivityMainBinding.etBundleCustomDepth: TextInputEditText
    get() = layoutToolWorkflow.etBundleCustomDepth

inline val ActivityMainBinding.btnBundleDepthIncrement: MaterialButton
    get() = layoutToolWorkflow.btnBundleDepthIncrement

inline val ActivityMainBinding.cardPrivacySummary: MaterialCardView
    get() = layoutToolWorkflow.cardPrivacySummary

inline val ActivityMainBinding.tvPrivacyProfileBadge: TextView
    get() = layoutToolWorkflow.tvPrivacyProfileBadge

inline val ActivityMainBinding.tvPrivacyImpact1: TextView
    get() = layoutToolWorkflow.tvPrivacyImpact1

inline val ActivityMainBinding.tvPrivacyImpact2: TextView
    get() = layoutToolWorkflow.tvPrivacyImpact2

inline val ActivityMainBinding.tvPrivacyImpact3: TextView
    get() = layoutToolWorkflow.tvPrivacyImpact3

inline val ActivityMainBinding.cardTelemetry: MaterialCardView
    get() = layoutToolWorkflow.cardTelemetry

inline val ActivityMainBinding.tvPhaseBadge: TextView
    get() = layoutToolWorkflow.tvPhaseBadge

inline val ActivityMainBinding.progressIndicator: LinearProgressIndicator
    get() = layoutToolWorkflow.progressIndicator

inline val ActivityMainBinding.tvStatusText: TextView
    get() = layoutToolWorkflow.tvStatusText

inline val ActivityMainBinding.tvProgressDetails: TextView
    get() = layoutToolWorkflow.tvProgressDetails

inline val ActivityMainBinding.btnToggleConsole: MaterialButton
    get() = layoutToolWorkflow.btnToggleConsole

inline val ActivityMainBinding.layoutConsoleBody: LinearLayout
    get() = layoutToolWorkflow.layoutConsoleBody

inline val ActivityMainBinding.btnCopyLogs: MaterialButton
    get() = layoutToolWorkflow.btnCopyLogs

inline val ActivityMainBinding.btnClearLogs: MaterialButton
    get() = layoutToolWorkflow.btnClearLogs

inline val ActivityMainBinding.btnExpandLogs: MaterialButton
    get() = layoutToolWorkflow.btnExpandLogs

inline val ActivityMainBinding.scrollConsole: NestedScrollView
    get() = layoutToolWorkflow.scrollConsole

inline val ActivityMainBinding.tvConsoleLog: TextView
    get() = layoutToolWorkflow.tvConsoleLog

inline val ActivityMainBinding.cardResultSummary: MaterialCardView
    get() = layoutToolWorkflow.cardResultSummary

inline val ActivityMainBinding.tvResultTitle: TextView
    get() = layoutToolWorkflow.tvResultTitle

inline val ActivityMainBinding.tvResultDetails: TextView
    get() = layoutToolWorkflow.tvResultDetails

inline val ActivityMainBinding.btnResultPreview: MaterialButton
    get() = layoutToolWorkflow.btnResultPreview

inline val ActivityMainBinding.btnResultSave: MaterialButton
    get() = layoutToolWorkflow.btnResultSave

inline val ActivityMainBinding.btnResultShare: MaterialButton
    get() = layoutToolWorkflow.btnResultShare
