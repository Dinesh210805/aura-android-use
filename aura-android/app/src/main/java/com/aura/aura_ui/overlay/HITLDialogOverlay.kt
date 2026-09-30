package com.aura.aura_ui.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat

/**
 * HITL (Human-in-the-Loop) Dialog Overlay.
 * 
 * Displays questions and options to the user during automation,
 * allowing the agent to request user input when needed.
 * 
 * Supports:
 * - Confirmation (Yes/No)
 * - Single choice (radio buttons)
 * - Multiple choice (checkboxes)
 * - Text input
 * - Notification (OK button)
 * - Action required (waiting state with Done button)
 */
class HITLDialogOverlay(
    private val context: Context
) : FrameLayout(context) {
    
    companion object {
        private const val TAG = "HITLDialog"

        // Question types matching backend
        const val TYPE_CONFIRMATION = "confirmation"
        const val TYPE_SINGLE_CHOICE = "single_choice"
        const val TYPE_MULTIPLE_CHOICE = "multiple_choice"
        const val TYPE_TEXT_INPUT = "text_input"
        const val TYPE_NOTIFICATION = "notification"
        const val TYPE_ACTION_REQUIRED = "action_required"
        // Hybrid: tap an option button OR type a custom answer
        const val TYPE_CHOICE_WITH_TEXT = "choice_with_text"
    }
    
    // UI Components
    private lateinit var dialogCard: CardView
    private lateinit var titleText: TextView
    private lateinit var messageText: TextView
    private lateinit var optionsContainer: LinearLayout
    private lateinit var inputField: EditText
    private lateinit var buttonContainer: LinearLayout
    private lateinit var cancelButton: Button
    private lateinit var confirmButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var timerText: TextView
    
    // State
    private var currentQuestionId: String? = null
    private var currentQuestionType: String? = null
    private var selectedOptions = mutableListOf<String>()
    private var selectedOption: String? = null
    private var responseCallback: ((Map<String, Any>) -> Unit)? = null
    private var timeoutHandler: Handler? = null
    private var timeoutRunnable: Runnable? = null
    private var remainingSeconds: Int = 0
    
    init {
        setupUI()
    }
    
    private fun setupUI() {
        // Full screen semi-transparent background
        setBackgroundColor(Color.parseColor("#80000000"))
        isClickable = true  // Consume clicks
        
        // Create card container
        dialogCard = CardView(context).apply {
            radius = 24f
            cardElevation = 16f
            setCardBackgroundColor(Color.parseColor("#1C1C1E"))  // iOS dark card
            useCompatPadding = true
        }
        
        val cardParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            marginStart = 32
            marginEnd = 32
        }
        addView(dialogCard, cardParams)
        
        // Inner content
        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 32)
        }
        dialogCard.addView(contentLayout, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        
        // Title
        titleText = TextView(context).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        contentLayout.addView(titleText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16 })
        
        // Message
        messageText = TextView(context).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#EBEBEB"))  // Secondary text
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }
        contentLayout.addView(messageText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 24 })
        
        // Timer text
        timerText = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#8E8E93"))
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        contentLayout.addView(timerText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16 })
        
        // Progress bar (for action_required)
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        contentLayout.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            8
        ).apply { bottomMargin = 16 })
        
        // Options container (for choices)
        optionsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        contentLayout.addView(optionsContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 24 })
        
        // Text input field
        inputField = EditText(context).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#636366"))
            background = createInputBackground()
            setPadding(32, 24, 32, 24)
            visibility = View.GONE
        }
        contentLayout.addView(inputField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 24 })
        
        // Button container
        buttonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        contentLayout.addView(buttonContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // Cancel button
        cancelButton = createStyledButton("Cancel", false).apply {
            visibility = View.GONE
        }
        buttonContainer.addView(cancelButton, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = 8 })
        
        // Confirm button
        confirmButton = createStyledButton("OK", true)
        buttonContainer.addView(confirmButton, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginStart = 8 })
        
        // Button click handlers
        cancelButton.setOnClickListener { onCancel() }
        confirmButton.setOnClickListener { onConfirm() }
        
        // Start hidden
        visibility = View.GONE
        alpha = 0f
    }
    
    private fun createInputBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f
            setColor(Color.parseColor("#2C2C2E"))
            setStroke(2, Color.parseColor("#48484A"))
        }
    }
    
    private fun createStyledButton(text: String, isPrimary: Boolean): Button {
        return Button(context).apply {
            this.text = text
            textSize = 16f
            isAllCaps = false
            setPadding(32, 20, 32, 20)
            
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f
                if (isPrimary) {
                    setColor(Color.WHITE)
                } else {
                    setColor(Color.parseColor("#2C2C2E"))
                    setStroke(1, Color.parseColor("#48484A"))
                }
            }
            background = bg
            setTextColor(if (isPrimary) Color.BLACK else Color.WHITE)
        }
    }
    
    /**
     * Show a HITL question dialog.
     */
    fun showQuestion(
        questionId: String,
        questionType: String,
        title: String,
        message: String,
        options: List<String> = emptyList(),
        defaultOption: String? = null,
        timeoutSeconds: Float = 60f,
        allowCancel: Boolean = true,
        actionType: String? = null,
        metadata: Map<String, Any> = emptyMap(),
        callback: (Map<String, Any>) -> Unit
    ) {
        Log.i(TAG, "🙋 Showing HITL dialog: type=$questionType, title=$title")
        
        currentQuestionId = questionId
        currentQuestionType = questionType
        responseCallback = callback
        selectedOptions.clear()
        selectedOption = defaultOption
        
        // Update UI
        titleText.text = title
        messageText.text = message
        
        // Configure based on question type
        when (questionType) {
            TYPE_CONFIRMATION -> {
                setupConfirmation()
            }
            TYPE_SINGLE_CHOICE -> {
                setupSingleChoice(options, defaultOption)
            }
            TYPE_MULTIPLE_CHOICE -> {
                setupMultipleChoice(options, metadata)
            }
            TYPE_TEXT_INPUT -> {
                setupTextInput(metadata)
            }
            TYPE_NOTIFICATION -> {
                setupNotification()
            }
            TYPE_ACTION_REQUIRED -> {
                setupActionRequired(actionType)
            }
            TYPE_CHOICE_WITH_TEXT -> {
                setupChoiceWithText(options, metadata)
            }
        }
        
        // Cancel button visibility
        cancelButton.visibility = if (allowCancel) View.VISIBLE else View.GONE
        
        // Setup timeout
        if (timeoutSeconds > 0) {
            startTimeout(timeoutSeconds.toInt())
        }
        
        // Animate in
        animateIn()
    }
    
    private fun setupConfirmation() {
        optionsContainer.visibility = View.GONE
        inputField.visibility = View.GONE
        progressBar.visibility = View.GONE
        
        confirmButton.text = "Yes"
        cancelButton.text = "No"
        cancelButton.visibility = View.VISIBLE
    }
    
    private fun setupSingleChoice(options: List<String>, defaultOption: String?) {
        optionsContainer.visibility = View.VISIBLE
        inputField.visibility = View.GONE
        progressBar.visibility = View.GONE
        
        optionsContainer.removeAllViews()
        val radioGroup = RadioGroup(context)
        
        options.forEachIndexed { index, option ->
            val radioButton = RadioButton(context).apply {
                text = option
                textSize = 16f
                setTextColor(Color.WHITE)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                setPadding(16, 20, 16, 20)
                id = View.generateViewId()
                
                if (option == defaultOption) {
                    isChecked = true
                    selectedOption = option
                }
            }
            radioButton.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedOption = option
                }
            }
            radioGroup.addView(radioButton)
        }
        
        optionsContainer.addView(radioGroup)
        confirmButton.text = "Select"
    }
    
    private fun setupMultipleChoice(options: List<String>, metadata: Map<String, Any>) {
        optionsContainer.visibility = View.VISIBLE
        inputField.visibility = View.GONE
        progressBar.visibility = View.GONE
        
        optionsContainer.removeAllViews()
        
        options.forEach { option ->
            val checkBox = CheckBox(context).apply {
                text = option
                textSize = 16f
                setTextColor(Color.WHITE)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                setPadding(16, 20, 16, 20)
            }
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedOptions.add(option)
                } else {
                    selectedOptions.remove(option)
                }
            }
            optionsContainer.addView(checkBox)
        }
        
        confirmButton.text = "Done"
    }
    
    private fun setupTextInput(metadata: Map<String, Any>) {
        optionsContainer.visibility = View.GONE
        inputField.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        
        val placeholder = metadata["placeholder"] as? String ?: "Enter text..."
        val default = metadata["default"] as? String ?: ""
        
        inputField.hint = placeholder
        inputField.setText(default)
        inputField.requestFocus()
        
        confirmButton.text = "Submit"
    }
    
    private fun setupChoiceWithText(options: List<String>, metadata: Map<String, Any>) {
        // Show radio buttons for the detected screen options + a text field below
        // so the user can either tap an option or type a custom answer.
        optionsContainer.visibility = View.VISIBLE
        inputField.visibility = View.VISIBLE
        progressBar.visibility = View.GONE

        optionsContainer.removeAllViews()

        // Radio group for the on-screen options
        val radioGroup = RadioGroup(context)
        options.forEachIndexed { _, option ->
            val radioButton = RadioButton(context).apply {
                text = option
                textSize = 15f
                setTextColor(Color.WHITE)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#64D2FF"))
                setPadding(16, 16, 16, 16)
                id = View.generateViewId()
            }
            radioButton.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedOption = option
                    // Clear the text field when an option is chosen
                    inputField.setText("")
                }
            }
            radioGroup.addView(radioButton)
        }
        optionsContainer.addView(radioGroup)

        // Divider label between radio group and text field
        val divider = TextView(context).apply {
            text = "— or type your own answer —"
            textSize = 12f
            setTextColor(Color.parseColor("#8E8E93"))
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 8)
        }
        optionsContainer.addView(divider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Text field placeholder from metadata
        val placeholder = metadata["placeholder"] as? String ?: "Or type your answer…"
        inputField.hint = placeholder
        inputField.setText("")

        // Typing clears the radio selection
        inputField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!s.isNullOrEmpty()) {
                    radioGroup.clearCheck()
                    selectedOption = null
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        confirmButton.text = "Submit"
    }

    private fun setupNotification() {
        optionsContainer.visibility = View.GONE
        inputField.visibility = View.GONE
        progressBar.visibility = View.GONE

        confirmButton.text = "OK"
        cancelButton.visibility = View.GONE
    }
    
    private fun setupActionRequired(actionType: String?) {
        optionsContainer.visibility = View.GONE
        inputField.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        
        // Add icon hint based on action type
        val actionHint = when (actionType) {
            "biometric_unlock" -> "🔐 Waiting for fingerprint..."
            "permission_grant" -> "📱 Waiting for permission..."
            "app_unlock" -> "🔑 Waiting for unlock..."
            else -> "⏳ Waiting for action..."
        }
        timerText.text = actionHint
        timerText.visibility = View.VISIBLE
        
        confirmButton.text = "Done"
    }
    
    private fun startTimeout(seconds: Int) {
        remainingSeconds = seconds
        timeoutHandler = Handler(Looper.getMainLooper())
        
        timerText.visibility = View.VISIBLE
        updateTimerText()
        
        timeoutRunnable = object : Runnable {
            override fun run() {
                remainingSeconds--
                if (remainingSeconds <= 0) {
                    onTimeout()
                } else {
                    updateTimerText()
                    timeoutHandler?.postDelayed(this, 1000)
                }
            }
        }
        timeoutHandler?.postDelayed(timeoutRunnable!!, 1000)
    }
    
    private fun updateTimerText() {
        if (currentQuestionType != TYPE_ACTION_REQUIRED) {
            timerText.text = "Timeout in ${remainingSeconds}s"
        }
    }
    
    private fun cancelTimeout() {
        timeoutRunnable?.let { timeoutHandler?.removeCallbacks(it) }
        timeoutHandler = null
        timeoutRunnable = null
    }
    
    private fun onConfirm() {
        cancelTimeout()
        
        val response = mutableMapOf<String, Any>(
            "question_id" to (currentQuestionId ?: ""),
            "success" to true,
            "cancelled" to false
        )
        
        when (currentQuestionType) {
            TYPE_CONFIRMATION -> {
                response["confirmed"] = true
            }
            TYPE_SINGLE_CHOICE -> {
                response["selected_option"] = selectedOption ?: ""
            }
            TYPE_MULTIPLE_CHOICE -> {
                response["selected_options"] = selectedOptions.toList()
            }
            TYPE_TEXT_INPUT -> {
                response["text_input"] = inputField.text.toString()
            }
            TYPE_NOTIFICATION -> {
                response["acknowledged"] = true
            }
            TYPE_ACTION_REQUIRED -> {
                response["action_completed"] = true
            }
            TYPE_CHOICE_WITH_TEXT -> {
                // Prefer typed text if the user wrote something; otherwise use the tapped option
                val typed = inputField.text.toString().trim()
                response["text_input"] = typed
                response["selected_option"] = if (typed.isEmpty()) selectedOption ?: "" else ""
            }
        }
        
        animateOut {
            responseCallback?.invoke(response)
        }
    }
    
    private fun onCancel() {
        cancelTimeout()
        
        val response = mutableMapOf<String, Any>(
            "question_id" to (currentQuestionId ?: ""),
            "success" to false,
            "cancelled" to true
        )
        
        // For confirmation, "No" is not a cancel, it's confirmed=false
        if (currentQuestionType == TYPE_CONFIRMATION) {
            response["success"] = true
            response["cancelled"] = false
            response["confirmed"] = false
        }
        
        animateOut {
            responseCallback?.invoke(response)
        }
    }
    
    private fun onTimeout() {
        Log.w(TAG, "⏰ HITL dialog timed out")
        
        val response = mutableMapOf<String, Any>(
            "question_id" to (currentQuestionId ?: ""),
            "success" to false,
            "cancelled" to false,
            "timed_out" to true
        )
        
        animateOut {
            responseCallback?.invoke(response)
        }
    }
    
    private fun animateIn() {
        visibility = View.VISIBLE
        alpha = 0f
        dialogCard.scaleX = 0.9f
        dialogCard.scaleY = 0.9f
        
        animate()
            .alpha(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
        
        dialogCard.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
    
    private fun animateOut(onComplete: () -> Unit) {
        animate()
            .alpha(0f)
            .setDuration(150)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = View.GONE
                    onComplete()
                }
            })
            .start()
        
        dialogCard.animate()
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(150)
            .start()
    }
    
    /**
     * Dismiss the dialog without sending a response.
     */
    fun dismiss() {
        cancelTimeout()
        animateOut { }
    }
}
