package com.clearlens.ai

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.transformer.*
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val bg=Color.rgb(12,16,27)
    private val panel=Color.rgb(24,31,47)
    private val mint=Color.rgb(114,240,205)
    private val muted=Color.rgb(168,181,200)
    private lateinit var root:LinearLayout
    private lateinit var cameraPage:LinearLayout
    private lateinit var cameraPreview:PreviewView
    private lateinit var cameraStatus:TextView
    private lateinit var captureButton:TextView
    private lateinit var editorPage:LinearLayout
    private lateinit var originalPreview:ImageView
    private lateinit var resultPreview:ImageView
    private lateinit var editStatus:TextView
    private lateinit var progress:ProgressBar
    private lateinit var strengthLabel:TextView
    private lateinit var enhanceButton:Button
    private lateinit var saveButton:Button
    private var imageCapture:ImageCapture?=null
    private var cameraControl:CameraControl?=null
    private lateinit var modePhotoLabel:TextView
    private lateinit var modeVideoLabel:TextView
    private var videoCapture:VideoCapture<Recorder>?=null
    private var recording:Recording?=null
    private var videoMode=false
    private var frontCamera=false
    private var flashEnabled=false
    private var hdrEnabled=true
    private var selectedVideo:Uri?=null
    private var original:Bitmap?=null
    private var enhanced:Bitmap?=null
    private var strength=45
    private val cameraExecutor:ExecutorService=Executors.newSingleThreadExecutor()

    private val permissionLauncher=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){ result ->
        if(result[Manifest.permission.CAMERA]==true) startCamera()
        else cameraStatus.text="Allow camera access in Android settings to use the camera."
    }
    private val imagePicker=registerForActivityResult(ActivityResultContracts.PickVisualMedia()){ uri-> if(uri!=null) loadImage(uri) }
    private val videoPicker=registerForActivityResult(ActivityResultContracts.PickVisualMedia()){ uri->
        if(uri!=null){selectedVideo=uri;original=null;enhanced=null;showEditor();editStatus.text="Video selected. Export an enhanced MP4.";addEditorControls(true)}
    }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.statusBarColor=bg;window.navigationBarColor=bg
        buildUI()
        if(hasCameraPermission())startCamera() else permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO))
    }

    private fun buildUI(){
        window.statusBarColor=Color.BLACK;window.navigationBarColor=Color.BLACK
        root=LinearLayout(this).apply{orientation=1;setBackgroundColor(Color.BLACK);setPadding(0,0,0,dp(4))}
        val top=LinearLayout(this).apply{orientation=0;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(6),dp(12),dp(6));setBackgroundColor(Color.BLACK)}
        top.addView(makeButton("⚡",false){flashEnabled=!flashEnabled;imageCapture?.flashMode=if(flashEnabled)ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF;toast(if(flashEnabled)"Flash on" else "Flash off")},LinearLayout.LayoutParams(dp(48),dp(44)))
        top.addView(makeButton("◎",false){toast("Tap the preview to focus where supported")},LinearLayout.LayoutParams(dp(48),dp(44)))
        top.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
        top.addView(makeButton("HDR",false){hdrEnabled=!hdrEnabled;toast(if(hdrEnabled)"HDR preference on" else "HDR preference off")},LinearLayout.LayoutParams(dp(58),dp(44)))
        top.addView(makeButton("⚙",false){showSettings()},LinearLayout.LayoutParams(dp(44),dp(44)))
        cameraPage=LinearLayout(this).apply{orientation=1;setBackgroundColor(Color.BLACK)}
        cameraPage.addView(top)
        cameraPreview=PreviewView(this).apply{scaleType=PreviewView.ScaleType.FILL_CENTER;implementationMode=PreviewView.ImplementationMode.COMPATIBLE;setBackgroundColor(Color.BLACK)}
        var touchStartX=0f
        cameraPreview.setOnTouchListener { _, event ->
            when(event.action) {
                android.view.MotionEvent.ACTION_DOWN -> { touchStartX=event.x; true }
                android.view.MotionEvent.ACTION_UP -> {
                    val delta=event.x-touchStartX
                    if(abs(delta)>dp(75)) setVideoMode(delta<0)
                    else try {
                        val point=cameraPreview.meteringPointFactory.createPoint(event.x,event.y)
                        cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                    } catch (_:Exception) {}
                    true
                }
                else -> true
            }
        }
        cameraPage.addView(cameraPreview,LinearLayout.LayoutParams(-1,0,1f))
        cameraStatus=label("Camera starting…",11f,Color.LTGRAY).apply{gravity=Gravity.CENTER;setPadding(dp(8),dp(3),dp(8),dp(3));setBackgroundColor(Color.BLACK)}
        cameraPage.addView(cameraStatus,LinearLayout.LayoutParams(-1,dp(22)))
        val modes=LinearLayout(this).apply{orientation=0;gravity=Gravity.CENTER;setPadding(dp(48),0,dp(48),0);setBackgroundColor(Color.BLACK)}
        modeVideoLabel=modeText("VIDEO"){setVideoMode(true)}
        modePhotoLabel=modeText("PHOTO"){setVideoMode(false)}
        modes.addView(modeVideoLabel,LinearLayout.LayoutParams(0,dp(48),1f))
        modes.addView(modePhotoLabel,LinearLayout.LayoutParams(0,dp(48),1f))
        cameraPage.addView(modes)
        refreshModeStrip()
        val bottom=LinearLayout(this).apply{orientation=0;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(18),dp(8),dp(18),dp(8));setBackgroundColor(Color.BLACK)}
        bottom.addView(makeButton("▧",false){
            if(videoMode) videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            else imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },LinearLayout.LayoutParams(dp(64),dp(60)))
        val center=FrameLayout(this)
        captureButton=TextView(this).apply{
            text="";gravity=Gravity.CENTER;background=rounded(Color.WHITE,50)
            foreground=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(Color.TRANSPARENT);setStroke(dp(3),Color.WHITE)}
            contentDescription="Take photo or start/stop recording"
            setOnClickListener{captureMedia()}
        }
        center.addView(captureButton,FrameLayout.LayoutParams(dp(76),dp(76),Gravity.CENTER))
        bottom.addView(center,LinearLayout.LayoutParams(0,dp(82),1f))
        bottom.addView(makeButton("↻",false){
            if(recording!=null) toast("Stop recording before switching cameras.")
            else { frontCamera=!frontCamera;startCamera() }
        },LinearLayout.LayoutParams(dp(64),dp(60)))
        cameraPage.addView(bottom)
        root.addView(cameraPage,LinearLayout.LayoutParams(-1,0,1f))
        editorPage=LinearLayout(this).apply{orientation=1;visibility=View.GONE;setPadding(dp(16),dp(6),dp(16),dp(20));setBackgroundColor(bg)}
        root.addView(ScrollView(this).apply{addView(editorPage)},LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
    }

    private fun startCamera(){
        if(!hasCameraPermission())return
        val future=ProcessCameraProvider.getInstance(this)
        future.addListener({
            try{
                val provider=future.get()
                val preview=Preview.Builder().build().also{it.setSurfaceProvider(cameraPreview.surfaceProvider)}
                val stillCapture=ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                val recorder=Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build()
                val movieCapture=VideoCapture.withOutput(recorder)
                imageCapture=stillCapture
                videoCapture=movieCapture
                provider.unbindAll()
                val camera=provider.bindToLifecycle(this,if(frontCamera)CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,preview,stillCapture,movieCapture)
                cameraControl=camera.cameraControl
                stillCapture.flashMode=if(flashEnabled && !frontCamera)ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                cameraStatus.text=if(frontCamera)"Ready · front camera" else "Ready · back camera"
            }catch(e:Exception){cameraStatus.text="Camera error: ${e.localizedMessage ?: "unknown"}"}
        },ContextCompat.getMainExecutor(this))
    }

    private fun setVideoMode(enabled:Boolean){
        videoMode=enabled
        refreshModeStrip()
        cameraStatus.text=if(enabled)"Video mode · swipe right for Photo" else "Photo mode · swipe left for Video"
        captureButton.text=if(recording!=null)"■" else ""
        captureButton.background=rounded(if(recording!=null)Color.RED else Color.WHITE,50)
    }

    private fun modeText(title:String,action:()->Unit)=TextView(this).apply{
        text=title;gravity=Gravity.CENTER;textSize=14f
        typeface=android.graphics.Typeface.create("sans-serif-medium",0)
        setOnClickListener{animate().alpha(0.7f).setDuration(80).withEndAction{alpha=1f;action()}.start()}
    }

    private fun refreshModeStrip(){
        if(!::modePhotoLabel.isInitialized || !::modeVideoLabel.isInitialized)return
        modePhotoLabel.text=if(!videoMode)"●  PHOTO" else "PHOTO"
        modeVideoLabel.text=if(videoMode)"●  VIDEO" else "VIDEO"
        modePhotoLabel.setTextColor(if(!videoMode)Color.WHITE else Color.LTGRAY)
        modeVideoLabel.setTextColor(if(videoMode)Color.WHITE else Color.LTGRAY)
        modePhotoLabel.textSize=if(!videoMode)15f else 13f
        modeVideoLabel.textSize=if(videoMode)15f else 13f
    }

    private fun captureMedia(){
        if(!hasCameraPermission()){permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO));return}
        if(videoMode){
            if(recording!=null){recording?.stop();captureButton.text="";captureButton.background=rounded(Color.WHITE,50);cameraStatus.text="Saving video…";return}
            val vc=videoCapture?:return toast("Camera is still starting.")
            val values=ContentValues().apply{
                put(MediaStore.Video.Media.DISPLAY_NAME,"ClearLens_${stamp()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE,"video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/ClearLens AI")
            }
            val output=MediaStoreOutputOptions.Builder(contentResolver,MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
            var pending=vc.output.prepareRecording(this,output)
            if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)pending=pending.withAudioEnabled()
            recording=pending.start(ContextCompat.getMainExecutor(this)){event->
                when(event){
                    is VideoRecordEvent.Start->{captureButton.text="■";captureButton.background=rounded(Color.RED,50);cameraStatus.text="Recording…"}
                    is VideoRecordEvent.Finalize->{recording=null;captureButton.text="";captureButton.background=rounded(Color.WHITE,50);cameraStatus.text=if(!event.hasError())"Video saved · Movies/ClearLens AI" else "Video save failed: ${event.error}"}
                }
            }
        }else{
            val ic=imageCapture?:return toast("Camera is still starting.")
            val tempFile=File(cacheDir,"capture_${System.currentTimeMillis()}.jpg")
            val output=ImageCapture.OutputFileOptions.Builder(tempFile).build()
            ic.takePicture(output,cameraExecutor,object:ImageCapture.OnImageSavedCallback{
                override fun onImageSaved(result:ImageCapture.OutputFileResults){processCapturedPhoto(tempFile)}
                override fun onError(error:ImageCaptureException){tempFile.delete();runOnUiThread{cameraStatus.text="Photo failed: ${error.message}";toast("Photo capture failed.")}}
            })
        }
    }

    private fun loadImage(uri:Uri){
        selectedVideo=null;enhanced=null;showEditor();editStatus.text="Loading photo…";progress.visibility=View.VISIBLE
        thread{
            try{
                val bmp=decodeScaledBitmap(uri)
                runOnUiThread{original=bmp;originalPreview.setImageBitmap(bmp);resultPreview.setImageDrawable(null);progress.visibility=View.GONE;editStatus.text="Ready to enhance";addEditorControls(false)}
            }catch(_:Exception){runOnUiThread{progress.visibility=View.GONE;editStatus.text="Could not open photo.";toast("Could not open photo.")}}
        }
    }

    private fun showEditor(){
        cameraPage.visibility=View.GONE;editorPage.visibility=View.VISIBLE;editorPage.removeAllViews();editorPage.setPadding(0,dp(6),0,dp(20))
        editorPage.addView(label("PHOTO LAB",10f,mint,true).apply{setPadding(0,dp(8),0,dp(8))})
        editorPage.addView(label("Make details feel clearer.",24f,Color.WHITE,true))
        editorPage.addView(label("Natural contrast and color controls.",13f,muted).apply{setPadding(0,dp(5),0,dp(12))})
        val row=LinearLayout(this).apply{orientation=0}
        originalPreview=ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_CROP;setBackgroundColor(panel);contentDescription="Original photo"}
        resultPreview=ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_CROP;setBackgroundColor(panel);contentDescription="Enhanced photo"}
        row.addView(previewCard("ORIGINAL",originalPreview),LinearLayout.LayoutParams(0,dp(220),1f).apply{setMargins(0,0,dp(5),0)})
        row.addView(previewCard("ENHANCED",resultPreview),LinearLayout.LayoutParams(0,dp(220),1f).apply{setMargins(dp(5),0,0,0)})
        editorPage.addView(row)
        progress=ProgressBar(this).apply{visibility=View.GONE;indeterminateTintList=android.content.res.ColorStateList.valueOf(mint)}
        editorPage.addView(progress)
        editStatus=label("Choose a photo to begin.",13f,Color.WHITE).apply{setPadding(dp(12),dp(12),dp(12),dp(12));background=rounded(panel,14)}
        editorPage.addView(editStatus,margin(-1,-2,0,dp(10),0,dp(10)))
        editorPage.addView(makeButton("← Back to camera",false){editorPage.visibility=View.GONE;cameraPage.visibility=View.VISIBLE})
    }

    private fun addEditorControls(isVideo:Boolean){
        val already=(0 until editorPage.childCount).any{(editorPage.getChildAt(it) as? Button)?.text?.toString()?.contains("Enhance photo")==true || (editorPage.getChildAt(it) as? Button)?.text?.toString()?.contains("Video improve")==true}
        if(already)return
        strengthLabel=label("Enhancement strength · $strength%",13f,Color.WHITE).apply{setPadding(0,dp(12),0,dp(2))}
        editorPage.addView(strengthLabel)
        editorPage.addView(SeekBar(this).apply{
            max=100;progress=strength;progressTintList=android.content.res.ColorStateList.valueOf(mint);thumbTintList=android.content.res.ColorStateList.valueOf(mint)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(seekBar:SeekBar?,value:Int,fromUser:Boolean){strength=value;strengthLabel.text="Enhancement strength · $value%"}
                override fun onStartTrackingTouch(seekBar:SeekBar?){}
                override fun onStopTrackingTouch(seekBar:SeekBar?){}
            })
        })
        if(isVideo)editorPage.addView(makeButton("Video improve & save MP4",true){enhanceVideo()})
        else{
            enhanceButton=makeButton("✦  Enhance photo",true){enhanceImage()};editorPage.addView(enhanceButton)
            saveButton=makeButton("Save enhanced photo",false){saveEnhancedImage()}.apply{isEnabled=false};editorPage.addView(saveButton)
        }
        editorPage.addView(label("Filters can improve contrast, but cannot recreate missing detail or repair moisture inside a physical camera lens.",11f,muted).apply{setPadding(0,dp(14),0,0)})
    }

    private fun previewCard(title:String,image:ImageView):LinearLayout{
        val box=LinearLayout(this).apply{orientation=1;setPadding(dp(8),dp(8),dp(8),dp(8));background=rounded(panel,18)}
        box.addView(label(title,10f,if(title=="ENHANCED")mint else muted,true).apply{setPadding(0,0,0,dp(7))})
        box.addView(image,LinearLayout.LayoutParams(-1,0,1f));return box
    }

    private fun enhanceImage(){
        val source=original?:return toast("Choose a photo first.")
        progress.visibility=View.VISIBLE;enhanceButton.isEnabled=false;saveButton.isEnabled=false;editStatus.text="Enhancing photo…";val amount=strength
        thread{
            try{val result=improveBitmap(source,amount);runOnUiThread{enhanced=result;resultPreview.setImageBitmap(result);progress.visibility=View.GONE;enhanceButton.isEnabled=true;saveButton.isEnabled=true;editStatus.text="Done · compare original and enhanced"}}
            catch(_:Exception){runOnUiThread{progress.visibility=View.GONE;enhanceButton.isEnabled=true;editStatus.text="Enhancement failed. Try a smaller photo."}}
        }
    }

    private fun improveBitmap(source:Bitmap,amount:Int):Bitmap{
        val result=source.copy(Bitmap.Config.ARGB_8888,true);val w=result.width;val h=result.height;val pixels=IntArray(w*h);result.getPixels(pixels,0,w,0,0,w,h)
        val a=amount.coerceIn(0,100)/100f;val contrast=1f+a*.48f;val saturation=1f+a*.28f
        for(i in pixels.indices){
            val c=pixels[i];val r=Color.red(c).toFloat();val g=Color.green(c).toFloat();val b=Color.blue(c).toFloat();val lum=.299f*r+.587f*g+.114f*b
            val nr=((lum+(r-lum)*saturation-127.5f)*contrast+127.5f+a*4).toInt().coerceIn(0,255)
            val ng=((lum+(g-lum)*saturation-127.5f)*contrast+127.5f+a*4).toInt().coerceIn(0,255)
            val nb=((lum+(b-lum)*saturation-127.5f)*contrast+127.5f+a*4).toInt().coerceIn(0,255)
            pixels[i]=Color.argb(Color.alpha(c),nr,ng,nb)
        }
        result.setPixels(pixels,0,w,0,0,w,h);return result
    }

    private fun saveEnhancedImage(){
        val bitmap=enhanced?:return toast("Enhance a photo first.")
        try{
            val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"ClearLens_${stamp()}_enhanced.jpg");put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/ClearLens AI");put(MediaStore.Images.Media.IS_PENDING,1)}
            val uri=contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:throw IOException("insert failed")
            contentResolver.openOutputStream(uri)?.use{if(!bitmap.compress(Bitmap.CompressFormat.JPEG,95,it))throw IOException("compress failed")}?:throw IOException("output failed")
            contentResolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)
            editStatus.text="Saved · Pictures/ClearLens AI";toast("Enhanced photo saved.")
        }catch(_:Exception){editStatus.text="Could not save photo.";toast("Save failed.")}
    }

    @OptIn(UnstableApi::class)
    private fun enhanceVideo(){
        val uri=selectedVideo?:return toast("Choose a video first.");val file=File(cacheDir,"clearlens_${System.currentTimeMillis()}.mp4")
        progress.visibility=View.VISIBLE;editStatus.text="Processing video… keep the app open."
        val effects=Effects(emptyList(),listOf(Contrast(strength/100f*.30f),Brightness(strength/100f*.05f)))
        val item=EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(effects).build()
        Transformer.Builder(this).setVideoMimeType(MimeTypes.VIDEO_H264).addListener(object:Transformer.Listener{
            override fun onCompleted(composition:Composition,result:ExportResult){
                thread{try{saveVideoToGallery(file);runOnUiThread{progress.visibility=View.GONE;editStatus.text="MP4 saved · Movies/ClearLens AI";toast("Enhanced MP4 saved.");file.delete()}}
                catch(_:Exception){runOnUiThread{progress.visibility=View.GONE;editStatus.text="Processed but could not save MP4."}}}
            }
            override fun onError(composition:Composition,result:ExportResult,error:ExportException){
                runOnUiThread{progress.visibility=View.GONE;editStatus.text="Video export failed: ${error.errorCodeName}";file.delete()}
            }
        }).build().start(item,file.absolutePath)
    }

    private fun saveVideoToGallery(file:File){
        if(!file.exists()||file.length()==0L)throw IOException("empty video")
        val values=ContentValues().apply{put(MediaStore.Video.Media.DISPLAY_NAME,"ClearLens_${stamp()}_enhanced.mp4");put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/ClearLens AI");put(MediaStore.Video.Media.IS_PENDING,1)}
        val uri=contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values)?:throw IOException("insert failed")
        contentResolver.openOutputStream(uri)?.use{out->FileInputStream(file).use{it.copyTo(out)}}?:throw IOException("output failed")
        contentResolver.update(uri,ContentValues().apply{put(MediaStore.Video.Media.IS_PENDING,0)},null,null)
    }

    private fun showSettings(){
        val options=arrayOf("Premium dark theme is active","Photo Lab · adjust enhancement strength","Photos: Pictures/ClearLens AI","Videos: Movies/ClearLens AI","About & limitations")
        android.app.AlertDialog.Builder(this).setTitle("ClearLens settings").setItems(options){_,which->
            if(which==4)android.app.AlertDialog.Builder(this).setTitle("About ClearLens AI").setMessage("Local image filters improve contrast and color. They cannot restore absent detail or repair physical moisture inside a camera lens.").setPositiveButton("OK",null).show()
        }.setNegativeButton("Close",null).show()
    }

    private fun decodeScaledBitmap(uri:Uri):Bitmap =
        decodeImageSource(ImageDecoder.createSource(contentResolver,uri))

    private fun decodeScaledBitmap(file:File):Bitmap =
        decodeImageSource(ImageDecoder.createSource(file))

    private fun decodeImageSource(source:ImageDecoder.Source):Bitmap =
        ImageDecoder.decodeBitmap(source){decoder,info,_->
            val width=info.size.width
            val height=info.size.height
            val scale=minOf(1f,2500f/max(width,height).toFloat())
            decoder.setTargetSize((width*scale).toInt().coerceAtLeast(1),(height*scale).toInt().coerceAtLeast(1))
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired=true
        }

    private fun processCapturedPhoto(file:File){
        runOnUiThread{cameraStatus.text="Photo captured · auto-enhancing…";captureButton.isEnabled=false}
        thread{
            var source:Bitmap?=null
            var enhancedImage:Bitmap?=null
            try{
                source=decodeScaledBitmap(file)
                enhancedImage=improveBitmap(source,if(hdrEnabled)68 else 48)
                saveBitmapToGallery(enhancedImage,"ClearLens_${stamp()}_enhanced.jpg")
                runOnUiThread{cameraStatus.text="Photo enhanced · Pictures/ClearLens AI";captureButton.isEnabled=true;toast("Photo enhanced and saved.")}
            }catch(_:Exception){
                try{
                    saveFileToGallery(file,"ClearLens_${stamp()}_original.jpg")
                    runOnUiThread{cameraStatus.text="Original saved; enhancement failed.";captureButton.isEnabled=true;toast("Original photo saved, but improvement failed.")}
                }catch(_:Exception){
                    runOnUiThread{cameraStatus.text="Photo could not be saved.";captureButton.isEnabled=true;toast("Photo processing failed.")}
                }
            }finally{
                if(enhancedImage!=null && enhancedImage!==source) enhancedImage.recycle()
                source?.recycle()
                file.delete()
            }
        }
    }

    private fun saveBitmapToGallery(bitmap:Bitmap,name:String){
        val values=ContentValues().apply{
            put(MediaStore.Images.Media.DISPLAY_NAME,name)
            put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/ClearLens AI")
            put(MediaStore.Images.Media.IS_PENDING,1)
        }
        val uri=contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:throw IOException("Could not create image")
        try{
            contentResolver.openOutputStream(uri)?.use{if(!bitmap.compress(Bitmap.CompressFormat.JPEG,95,it))throw IOException("Could not encode image")}?:throw IOException("Could not open image output")
            contentResolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)
        }catch(e:Exception){contentResolver.delete(uri,null,null);throw e}
    }

    private fun saveFileToGallery(file:File,name:String){
        if(!file.exists()||file.length()==0L)throw IOException("Image file is empty")
        val values=ContentValues().apply{
            put(MediaStore.Images.Media.DISPLAY_NAME,name)
            put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/ClearLens AI")
            put(MediaStore.Images.Media.IS_PENDING,1)
        }
        val uri=contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:throw IOException("Could not create image")
        try{
            contentResolver.openOutputStream(uri)?.use{output->FileInputStream(file).use{it.copyTo(output)}}?:throw IOException("Could not write image")
            contentResolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)
        }catch(e:Exception){contentResolver.delete(uri,null,null);throw e}
    }
    private fun label(text:String,size:Float,color:Int,bold:Boolean=false)=TextView(this).apply{this.text=text; this.textSize=size;setTextColor(color);if(bold)typeface=android.graphics.Typeface.create("sans-serif-medium",0)}
    private fun makeButton(text:String,primary:Boolean,action:()->Unit)=Button(this).apply{this.text=text;isAllCaps=false;textSize=13f;typeface=android.graphics.Typeface.create("sans-serif-medium",0);setTextColor(if(primary)bg else Color.WHITE);background=rounded(if(primary)mint else panel,15);stateListAnimator=null;setPadding(dp(8),dp(4),dp(8),dp(4));setOnClickListener{action()}}
    private fun rounded(color:Int,radius:Int)=android.graphics.drawable.GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat()}
    private fun margin(w:Int,h:Int,l:Int,t:Int,r:Int,b:Int)=LinearLayout.LayoutParams(w,h).apply{setMargins(l,t,r,b)}
    private fun hasCameraPermission()=ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun stamp()=SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(System.currentTimeMillis())
    private fun toast(message:String)=Toast.makeText(this,message,Toast.LENGTH_LONG).show()
    override fun onDestroy(){recording?.stop();cameraExecutor.shutdown();super.onDestroy()}
}
