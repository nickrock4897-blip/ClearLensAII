package com.clearlens.ai

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

class MainActivity : ComponentActivity() {
    private val bg=Color.rgb(12,16,27); private val card=Color.rgb(24,31,47); private val accent=Color.rgb(114,240,205); private val muted=Color.rgb(168,181,200)
    private lateinit var root:LinearLayout; private lateinit var cameraPage:LinearLayout; private lateinit var preview:PreviewView
    private lateinit var cameraStatus:TextView; private lateinit var capture:Button; private lateinit var editor:LinearLayout
    private lateinit var originalView:ImageView; private lateinit var resultView:ImageView; private lateinit var editStatus:TextView
    private lateinit var progress:ProgressBar; private lateinit var enhance:Button; private lateinit var save:Button; private lateinit var strengthText:TextView
    private var imageCapture:ImageCapture?=null; private var videoCapture:VideoCapture<Recorder>?=null; private var recording:Recording?=null
    private var videoMode=false; private var selectedVideo:Uri?=null; private var original:Bitmap?=null; private var enhanced:Bitmap?=null; private var strength=45
    private val executor:ExecutorService=Executors.newSingleThreadExecutor()

    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){ if(it[Manifest.permission.CAMERA]==true) startCamera() else cameraStatus.text="Allow camera access in Android settings to take photos." }
    private val imagePicker=registerForActivityResult(ActivityResultContracts.PickVisualMedia()){ if(it!=null) loadImage(it) }
    private val videoPicker=registerForActivityResult(ActivityResultContracts.PickVisualMedia()){ uri-> if(uri!=null){selectedVideo=uri;original=null;enhanced=null;showEditor();editStatus.text="Video selected. Export an enhanced MP4."; editorControls(true)} }

    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);window.statusBarColor=bg;window.navigationBarColor=bg;buildUI();if(hasCamera())startCamera() else permissions.launch(arrayOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO))}
    private fun buildUI(){
        root=LinearLayout(this).apply{orientation=1;setPadding(dp(16),dp(10),dp(16),dp(14));setBackgroundColor(bg)}
        val header=LinearLayout(this).apply{orientation=0;gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(4),0,dp(14))}
        header.addView(label("◈",30f,accent,true),LinearLayout.LayoutParams(dp(45),dp(45)))
        val names=LinearLayout(this).apply{orientation=1;setPadding(dp(10),0,0,0)}
        names.addView(label("ClearLens AI",22f,Color.WHITE,true));names.addView(label("CAMERA STUDIO",10f,accent,true))
        header.addView(names,LinearLayout.LayoutParams(0,-2,1f));header.addView(button("⚙",false){settings()},LinearLayout.LayoutParams(dp(52),dp(46));root.addView(header)
        cameraPage=LinearLayout(this).apply{orientation=1}
        val hero=LinearLayout(this).apply{orientation=1;setPadding(dp(16),dp(15),dp(16),dp(15));background=rounded(card,20)}
        hero.addView(label("Capture something clearer.",21f,Color.WHITE,true));hero.addView(label("Live camera · photo cleanup · video export",12f,muted).apply{setPadding(0,dp(5),0,0)})
        cameraPage.addView(hero,margin(-1,-2,0,0,0,10))
        preview=PreviewView(this).apply{scaleType=PreviewView.ScaleType.FILL_CENTER;implementationMode=PreviewView.ImplementationMode.COMPATIBLE;setBackgroundColor(Color.BLACK)}
        cameraPage.addView(preview,LinearLayout.LayoutParams(-1,dp(330)))
        cameraStatus=label("Camera starting…",13f,Color.WHITE).apply{setPadding(dp(12),dp(10),dp(12),dp(10));background=rounded(card,14)}
        cameraPage.addView(cameraStatus,margin(-1,-2,0,dp(8),0,dp(4)))
        val modes=LinearLayout(this).apply{orientation=0}
        modes.addView(button("PHOTO",true){setMode(false)},LinearLayout.LayoutParams(0,dp(46),1f).apply{setMargins(0,dp(4),dp(5),dp(5))})
        modes.addView(button("VIDEO",false){setMode(true)},LinearLayout.LayoutParams(0,dp(46),1f).apply{setMargins(dp(5),dp(4),0,dp(5))})
        cameraPage.addView(modes);capture=button("●   TAKE PHOTO",true){captureMedia()};cameraPage.addView(capture,margin(-1,dp(54),0,dp(3),0,dp(8)))
        val imports=LinearLayout(this).apply{orientation=0}
        imports.addView(button("Import photo",false){imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},LinearLayout.LayoutParams(0,dp(46),1f).apply{setMargins(0,0,dp(5),0)})
        imports.addView(button("Import video",false){videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))},LinearLayout.LayoutParams(0,dp(46),1f).apply{setMargins(dp(5),0,0,0)})
        cameraPage.addView(imports);root.addView(cameraPage,LinearLayout.LayoutParams(-1,0,1f))
        editor=LinearLayout(this).apply{orientation=1;visibility=View.GONE};val scroll=ScrollView(this).apply{addView(editor)}
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
    }
    private fun startCamera(){
        if(!hasCamera())return
        val future=ProcessCameraProvider.getInstance(this)
        future.addListener({try{
            val provider=future.get();val p=Preview.Builder().build().also{it.setSurfaceProvider(preview.surfaceProvider)}
            imageCapture=ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            val recorder=Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build();videoCapture=VideoCapture.withOutput(recorder)
            provider.unbindAll();provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,p,imageCapture,videoCapture);cameraStatus.text="Ready · back camera"
        }catch(e:Exception){cameraStatus.text="Camera error: ${e.localizedMessage}"}},ContextCompat.getMainExecutor(this))
    }
    private fun setMode(video:Boolean){videoMode=video;capture.text=if(video)"●   START RECORDING" else "●   TAKE PHOTO";cameraStatus.text=if(video)"Video mode · recordings save to Movies/ClearLens AI" else "Photo mode · photos save to Pictures/ClearLens AI"}
    private fun captureMedia(){
        if(!hasCamera()){permissions.launch(arrayOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO));return}
        if(videoMode){
            if(recording!=null){recording?.stop();recording=null;capture.text="●   START RECORDING";cameraStatus.text="Saving video…";return}
            val vc=videoCapture?:return toast("Camera is still starting.")
            val values=ContentValues().apply{put(MediaStore.Video.Media.DISPLAY_NAME,"ClearLens_${stamp()}.mp4");put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/ClearLens AI")}
            val opts=MediaStoreOutputOptions.Builder(contentResolver,MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
            var pending=vc.output.prepareRecording(this,opts)
            if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)pending=pending.withAudioEnabled()
            recording=pending.start(ContextCompat.getMainExecutor(this)){event->when(event){
                is VideoRecordEvent.Start->{capture.text="■   STOP RECORDING";cameraStatus.text="Recording…"}
                is VideoRecordEvent.Finalize->{recording=null;capture.text="●   START RECORDING";cameraStatus.text=if(!event.hasError())"Video saved · Movies/ClearLens AI" else "Video save failed: ${event.error}"}
            }}
        }else{
            val ic=imageCapture?:return toast("Camera is still starting.")
            val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"ClearLens_${stamp()}.jpg");put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/ClearLens AI")}
            val output=ImageCapture.OutputFileOptions.Builder(contentResolver,MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values).build()
            ic.takePicture(output,executor,object:ImageCapture.OnImageSavedCallback{
                override fun onImageSaved(result:ImageCapture.OutputFileResults){runOnUiThread{cameraStatus.text="Photo saved · Pictures/ClearLens AI";toast("Photo captured.")}}
                override fun onError(e:ImageCaptureException){runOnUiThread{cameraStatus.text="Photo failed: ${e.message}";toast("Photo capture failed.")}}
            })
        }
    }
    private fun loadImage(uri:Uri){selectedVideo=null;enhanced=null;showEditor();editStatus.text="Loading photo…";progress.visibility=View.VISIBLE;thread{try{val bmp=decode(uri);runOnUiThread{original=bmp;originalView.setImageBitmap(bmp);resultView.setImageDrawable(null);progress.visibility=View.GONE;editStatus.text="Ready to enhance";editorControls(false)}}catch(_:Exception){runOnUiThread{progress.visibility=View.GONE;editStatus.text="Could not open photo.";toast("Could not open photo.")}}}}
    private fun showEditor(){
        cameraPage.visibility=View.GONE;editor.visibility=View.VISIBLE;editor.removeAllViews();editor.setPadding(0,dp(6),0,dp(20))
        editor.addView(label("PHOTO LAB",10f,accent,true).apply{setPadding(0,dp(8),0,dp(8))})
        editor.addView(label("Make details feel clearer.",24f,Color.WHITE,true))
        editor.addView(label("Natural contrast and color controls.",13f,muted).apply{setPadding(0,dp(5),0,dp(12))})
        val row=LinearLayout(this).apply{orientation=0}
        originalView=ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_CROP;setBackgroundColor(card);contentDescription="Original photo"}
        resultView=ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_CROP;setBackgroundColor(card);contentDescription="Enhanced photo"}
        row.addView(previewCard("ORIGINAL",originalView),LinearLayout.LayoutParams(0,dp(220),1f).apply{setMargins(0,0,dp(5),0)})
        row.addView(previewCard("ENHANCED",resultView),LinearLayout.LayoutParams(0,dp(220),1f).apply{setMargins(dp(5),0,0,0)})
        editor.addView(row);progress=ProgressBar(this).apply{visibility=View.GONE;indeterminateTintList=android.content.res.ColorStateList.valueOf(accent)};editor.addView(progress)
        editStatus=label("Choose a photo to begin.",13f,Color.WHITE).apply{setPadding(dp(12),dp(12),dp(12),dp(12));background=rounded(card,14)}
        editor.addView(editStatus,margin(-1,-2,0,dp(10),0,dp(10)))
        editor.addView(button("← Back to camera",false){editor.visibility=View.GONE;cameraPage.visibility=View.VISIBLE})
    }
    private fun editorControls(isVideo:Boolean){
        if((0 until editor.childCount).any{(editor.getChildAt(it) as? Button)?.text?.toString()?.contains("Enhance photo")==true || (editor.getChildAt(it) as? Button)?.text?.toString()?.contains("Video improve")==true})return
        strengthText=label("Enhancement strength · $strength%",13f,Color.WHITE).apply{setPadding(0,dp(12),0,dp(2))};editor.addView(strengthText)
        editor.addView(SeekBar(this).apply{max=100;progress=strength;progressTintList=android.content.res.ColorStateList.valueOf(accent);thumbTintList=android.content.res.ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,v:Int,from:Boolean){strength=v;strengthText.text="Enhancement strength · $v%"};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){}})})
        if(isVideo){editor.addView(button("Video improve & save MP4",true){enhanceVideo()})}
        else{enhance=button("✦  Enhance photo",true){enhanceImage()};editor.addView(enhance);save=button("Save enhanced photo",false){saveImage()}.apply{isEnabled=false};editor.addView(save)}
        editor.addView(label("Note: filters can improve contrast, but cannot recreate missing detail or repair moisture inside a physical camera lens.",11f,muted).apply{setPadding(0,dp(14),0,0)})
    }
    private fun previewCard(title:String,img:ImageView):LinearLayout{val box=LinearLayout(this).apply{orientation=1;setPadding(dp(8),dp(8),dp(8),dp(8));background=rounded(card,18)};box.addView(label(title,10f,if(title=="ENHANCED")accent else muted,true).apply{setPadding(0,0,0,dp(7))});box.addView(img,LinearLayout.LayoutParams(-1,0,1f));return box}
    private fun enhanceImage(){
        val src=original?:return toast("Choose a photo first.");progress.visibility=View.VISIBLE;enhance.isEnabled=false;save.isEnabled=false;editStatus.text="Enhancing photo…";val amount=strength
        thread{try{val bmp=improve(src,amount);runOnUiThread{enhanced=bmp;resultView.setImageBitmap(bmp);progress.visibility=View.GONE;enhance.isEnabled=true;save.isEnabled=true;editStatus.text="Done · compare original and enhanced"}}catch(_:Exception){runOnUiThread{progress.visibility=View.GONE;enhance.isEnabled=true;editStatus.text="Enhancement failed. Try a smaller photo."}}}
    }
    private fun improve(src:Bitmap,value:Int):Bitmap{
        val out=src.copy(Bitmap.Config.ARGB_8888,true);val w=out.width;val h=out.height;val px=IntArray(w*h);out.getPixels(px,0,w,0,0,w,h)
        val a=value.coerceIn(0,100)/100f;val contrast=1f+a*.48f;val sat=1f+a*.28f
        for(i in px.indices){val c=px[i];val r=Color.red(c).toFloat();val g=Color.green(c).toFloat();val b=Color.blue(c).toFloat();val lum=.299f*r+.587f*g+.114f*b
            val nr=((lum+(r-lum)*sat-127.5f)*contrast+127.5f+a*4).toInt().coerceIn(0,255)
            val ng=((lum+(g-lum)*sat-127.5f)*contrast+127.5f+a*4).toInt().coerceIn(0,255)
            val nb=((lum+(b-lum)*sat-127.5f)*contrast+127.5f+a*4).toInt().coerceIn(0,255);px[i]=Color.argb(Color.alpha(c),nr,ng,nb)}
        out.setPixels(px,0,w,0,0,w,h);return out
    }
    private fun saveImage(){val bmp=enhanced?:return toast("Enhance a photo first.");try{val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"ClearLens_${stamp()}_enhanced.jpg");put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/ClearLens AI");put(MediaStore.Images.Media.IS_PENDING,1)}
        val uri=contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:throw IOException("insert failed")
        contentResolver.openOutputStream(uri)?.use{if(!bmp.compress(Bitmap.CompressFormat.JPEG,95,it))throw IOException("compress failed")}?:throw IOException("output failed")
        contentResolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null);editStatus.text="Saved · Pictures/ClearLens AI";toast("Enhanced photo saved.")
    }catch(_:Exception){editStatus.text="Could not save photo.";toast("Save failed.")}}
    @OptIn(UnstableApi::class) private fun enhanceVideo(){
        val uri=selectedVideo?:return toast("Choose a video first.");val file=File(cacheDir,"clearlens_${System.currentTimeMillis()}.mp4");progress.visibility=View.VISIBLE;editStatus.text="Processing video… keep app open."
        val effects=Effects(emptyList(),listOf(Contrast(strength/100f*.30f),Brightness(strength/100f*.05f)))
        val item=EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(effects).build()
        Transformer.Builder(this).setVideoMimeType(MimeTypes.VIDEO_H264).addListener(object:Transformer.Listener{
            override fun onCompleted(composition:Composition,result:ExportResult){thread{try{saveVideo(file);runOnUiThread{progress.visibility=View.GONE;editStatus.text="MP4 saved · Movies/ClearLens AI";toast("Enhanced MP4 saved.");file.delete()}}catch(_:Exception){runOnUiThread{progress.visibility=View.GONE;editStatus.text="Processed but could not save MP4."}}}
            override fun onError(composition:Composition,result:ExportResult,error:ExportException){runOnUiThread{progress.visibility=View.GONE;editStatus.text="Video export failed: ${error.errorCodeName}";file.delete()}}
        }).build().start(item,file.absolutePath)
    }
    private fun saveVideo(file:File){if(!file.exists()||file.length()==0L)throw IOException("empty video")
        val values=ContentValues().apply{put(MediaStore.Video.Media.DISPLAY_NAME,"ClearLens_${stamp()}_enhanced.mp4");put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/ClearLens AI");put(MediaStore.Video.Media.IS_PENDING,1)}
        val uri=contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values)?:throw IOException("insert failed")
        contentResolver.openOutputStream(uri)?.use{o->FileInputStream(file).use{it.copyTo(o)}}?:throw IOException("output failed")
        contentResolver.update(uri,ContentValues().apply{put(MediaStore.Video.Media.IS_PENDING,0)},null,null)
    }
    private fun settings(){val options=arrayOf("Premium dark theme is active","Photo Lab · adjust enhancement strength","Photos: Pictures/ClearLens AI","Videos: Movies/ClearLens AI","About & limitations")
        android.app.AlertDialog.Builder(this).setTitle("ClearLens settings").setItems(options){_,which->if(which==4)android.app.AlertDialog.Builder(this).setTitle("About ClearLens AI").setMessage("Local image filters improve contrast and color. They cannot restore absent detail or repair physical lens moisture.").setPositiveButton("OK",null).show()}.setNegativeButton("Close",null).show()}
    private fun decode(uri:Uri):Bitmap{val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,bounds)}?:throw IOException("open failed");var sample=1;while(max(bounds.outWidth,bounds.outHeight)/sample>2500)sample*=2
        return contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})}?:throw IOException("decode failed")}
    private fun label(text:String,size:Float,color:Int,bold:Boolean=false)=TextView(this).apply{this.text=text;textSize=size;setTextColor(color);if(bold)typeface=android.graphics.Typeface.create("sans-serif-medium",0)}
    private fun button(text:String,primary:Boolean,action:()->Unit)=Button(this).apply{this.text=text;isAllCaps=false;textSize=13f;typeface=android.graphics.Typeface.create("sans-serif-medium",0);setTextColor(if(primary)bg else Color.WHITE);background=rounded(if(primary)accent else card,15);stateListAnimator=null;setPadding(dp(8),dp(4),dp(8),dp(4));setOnClickListener{action()}}
    private fun rounded(color:Int,radius:Int)=android.graphics.drawable.GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat()}
    private fun margin(w:Int,h:Int,l:Int,t:Int,r:Int,b:Int)=LinearLayout.LayoutParams(w,h).apply{setMargins(l,t,r,b)}
    private fun hasCamera()=ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun stamp()=SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(System.currentTimeMillis())
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    override fun onDestroy(){recording?.stop();executor.shutdown();super.onDestroy()}
}
