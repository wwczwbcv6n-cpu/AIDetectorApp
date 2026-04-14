package com.myapplication.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myapplication.common.ImagePicker
import com.myapplication.common.ImagePickerFactory
import com.myapplication.common.PyTorchModel // Import PyTorchModel
import com.myapplication.common.SharedViewModel // Import SharedViewModel

@Composable
fun App(
    pyTorchModel: PyTorchModel, // Pass PyTorchModel from platform-specific entry points
    context: Any // Pass platform context
) {
    val viewModel = remember { SharedViewModel(pyTorchModel) }
    val imagePicker = ImagePickerFactory().createPicker()

    // Initialize the model
    LaunchedEffect(Unit) {
        // Need to ensure the model asset name matches what's used in optimize_model.py
        pyTorchModel.loadModel("ai_detector_model_pytorch_script.ptl")
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("AI Image Detector", style = MaterialTheme.typography.h4)

                Button(
                    onClick = {
                        imagePicker.pickImage { imageBytesList ->
                            imageBytesList.firstOrNull()?.let {
                                viewModel.analyzeImage(it)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Pick Image from Gallery")
                }

                AnimatedVisibility(viewModel.isLoading) {
                    CircularProgressIndicator()
                }

                viewModel.errorMessage?.let {
                    Text(it, color = Color.Red)
                }

                viewModel.detectedImage?.let { imageBitmap ->
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "Selected Image",
                        modifier = Modifier.fillMaxWidth(0.8f).aspectRatio(imageBitmap.width.toFloat() / imageBitmap.height.toFloat()),
                        contentScale = ContentScale.Fit
                    )

                    Spacer(Modifier.height(16.dp))

                    viewModel.detectionResult?.let { result ->
                        val statusText = if (result.isAI) "Likely AI Generated" else "Likely Real"
                        val statusColor = if (result.isAI) Color.Red else Color.Green
                        Text(
                            text = "Status: $statusText",
                            color = statusColor,
                            fontSize = 20.sp
                        )
                        Text(
                            text = "Confidence: ${(result.confidence * 100).toInt()}%",
                            fontSize = 18.sp
                        )
                    }
                }
            }
        }
    }
}