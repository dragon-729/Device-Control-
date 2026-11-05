package com.droidlink.presentation.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.droidlink.R
import com.droidlink.presentation.common.LoadingDots
import com.droidlink.presentation.theme.Navy
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        kotlinx.coroutines.delay(600)
        onFinished()
    }
    Column(
        Modifier.fillMaxSize().background(Navy),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painterResource(R.drawable.droidlink_logo),
            contentDescription = "DroidLink logo",
            modifier = Modifier.size(220.dp).clip(RoundedCornerShape(32.dp)).scale(0.8f + 0.2f * progress.value).alpha(progress.value),
        )
        Spacer(Modifier.height(24.dp))
        Text("DroidLink", style = MaterialTheme.typography.headlineMedium, color = Color.White, modifier = Modifier.alpha(progress.value))
        Spacer(Modifier.height(20.dp))
        LoadingDots()
    }
}
