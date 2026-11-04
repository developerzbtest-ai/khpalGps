package io.github.jqssun.gpssetter.ui


import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.utils.ext.getAddress
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.SupportMapFragment
import org.maplibre.android.maps.Style
import android.view.Gravity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

typealias CustomLatLng = LatLng

class MapActivity: BaseMapActivity(), OnMapReadyCallback, MapLibreMap.OnMapClickListener {

    private lateinit var mMap: MapLibreMap
    private var mLatLng: LatLng? = null
    private var mMarker: Marker? = null

    override fun hasMarker(): Boolean {
        // TODO: if (!mMarker?.isVisible!!){
        if (mMarker != null) {
            return true
        }
        return false
    }
    private fun updateMarker(it: LatLng) {
        // TODO: mMarker?.isVisible = true
        if (mMarker == null) {
            mMarker = mMap.addMarker(
                MarkerOptions().position(it)
            )
        } else {
            mMarker?.position = it!!
        }
    }
    private fun removeMarker() {
        mMarker?.remove() // mMarker?.isVisible = false
        mMarker = null
    }
    override fun initializeMap() {
        MapLibre.getInstance(this)
        // val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment?
        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.map, mapFragment)
            .commit()
        mapFragment?.getMapAsync(this)
    }
    override fun moveMapToNewLocation(moveNewLocation: Boolean) {
        if (moveNewLocation) {
            mLatLng = LatLng(lat, lon)
            mLatLng.let { latLng ->
                // mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng!!, 12.0f.toDouble()))
                mMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                        .target(latLng!!)
                        .zoom(12.0f.toDouble())
                        .bearing(0f.toDouble())
                        .tilt(0f.toDouble())
                        .build()
                ))
                mMarker?.apply {
                    position = latLng
                    // TODO:
                    // isVisible = true
                    // showInfoWindow()
                }
            }
        }
    }
    override fun onMapReady(mapLibreMap: MapLibreMap) {
        mMap = mapLibreMap
        with(mMap){


            // key-free raster tiles (OpenStreetMap / Esri / OpenTopoMap)
            applyStyle(satMaxZoom, true)
            // TODO: fix bug with drawer
            uiSettings.setAllGesturesEnabled(true)
            uiSettings.setCompassEnabled(true)
            uiSettings.setCompassMargins(0,480,120,0)
            uiSettings.setLogoEnabled(true)
            uiSettings.setLogoGravity(Gravity.BOTTOM or Gravity.START)
            uiSettings.setLogoMargins(dp(16), 0, 0, dp(48))
            uiSettings.setAttributionEnabled(true)
            uiSettings.setAttributionGravity(Gravity.BOTTOM or Gravity.END)
            uiSettings.setAttributionMargins(0, 0, dp(16), dp(48))
            // uiSettings.setAttributionMargins(80,0,0,80)
            // setPadding(0,0,0,80)


            val zoom = 12.0f
            lat = viewModel.getLat
            lon  = viewModel.getLng
            mLatLng = LatLng(lat, lon)
            mLatLng.let {
                updateMarker(it!!)
                // TODO: MarkerOptions().position(it!!)
                // .draggable(false).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED).visible(false)
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, zoom.toDouble()))
            }

            addOnMapClickListener(this@MapActivity)
            addOnCameraIdleListener { onCameraIdle() }
            if (viewModel.isStarted){
                mMarker?.let {
                    // TODO:
                    // it.isVisible = true
                    // it.showInfoWindow()
                }
            }
        }
    }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private var satMaxZoom = MapStyles.DEFAULT_SAT_MAX_ZOOM
    private var lastProbeKey: String? = null
    private var probeJob: Job? = null

    private fun applyStyle(maxZoom: Int, firstTime: Boolean) {
        val json = MapStyles.styleJson(viewModel.mapType, maxZoom)
        mMap.setStyle(Style.Builder().fromJson(json)) { style ->
            activateLocationDot(style, firstTime)
        }
    }

    @SuppressLint("MissingPermission")
    private fun activateLocationDot(style: Style, firstTime: Boolean) {
        if (ActivityCompat.checkSelfPermission(this@MapActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val locationComponent = mMap.locationComponent
            locationComponent.activateLocationComponent(
                LocationComponentActivationOptions.builder(this@MapActivity, style)
                    .useDefaultLocationEngine(true)
                    .build()
            )
            locationComponent.isLocationComponentEnabled = true
            // the first time follows the user (as before); after a style change never re-centre the camera
            locationComponent.cameraMode = if (firstTime) CameraMode.TRACKING else CameraMode.NONE
            locationComponent.renderMode = RenderMode.COMPASS
        } else if (firstTime) {
            ActivityCompat.requestPermissions(this@MapActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 99)
        }
    }

    /** Satellite / Hybrid: find the real maximum imagery zoom here, so we never show grey tiles. */
    private fun onCameraIdle() {
        if (!MapStyles.isEsri(viewModel.mapType)) return
        val pos = mMap.cameraPosition
        if (pos.zoom < 14.0) return
        val target = pos.target ?: return
        val key = EsriProbe.cacheKey(target.latitude, target.longitude)
        if (key == lastProbeKey) return
        lastProbeKey = key
        probeJob?.cancel()
        probeJob = lifecycleScope.launch {
            val z = withContext(Dispatchers.IO) { EsriProbe.realMaxZoom(target.latitude, target.longitude) }
            if (z != satMaxZoom) {
                satMaxZoom = z
                applyStyle(z, false)
            }
        }
    }

    override fun zoomMap(zoomIn: Boolean) {
        if (!::mMap.isInitialized) return
        mMap.animateCamera(if (zoomIn) CameraUpdateFactory.zoomIn() else CameraUpdateFactory.zoomOut())
    }

    override fun onMapClick(latLng: LatLng): Boolean {
        mLatLng = latLng
        mMarker?.let { marker ->
            mLatLng.let {
                // marker.isVisible = true
                updateMarker(it!!)
                mMap.animateCamera(CameraUpdateFactory.newLatLng(it))
                lat = it.latitude
                lon = it.longitude
            }
        }
        return true
    }



    override fun getActivityInstance(): BaseMapActivity {
        return this@MapActivity
    }

    @SuppressLint("MissingPermission")
    override fun setupButtons(){
        binding.addfavorite.setOnClickListener {
            addFavoriteDialog()
        }
        binding.getlocation.setOnClickListener {
            getLastLocation()
        }

        if (viewModel.isStarted) {
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
        }

        binding.startButton.setOnClickListener {
            viewModel.update(true, lat, lon)
            mLatLng.let {
                updateMarker(it!!)
            }
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
            lifecycleScope.launch {
                mLatLng?.getAddress(getActivityInstance())?.let { address ->
                    address.collect{ value ->
                        showStartNotification(value)
                    }
                }
            }
            showToast(getString(R.string.location_set))
        }
        binding.stopButton.setOnClickListener {
            mLatLng.let {
                viewModel.update(false, it!!.latitude, it.longitude)
            }
            removeMarker()
            binding.stopButton.visibility = View.GONE
            binding.startButton.visibility = View.VISIBLE
            cancelNotification()
            showToast(getString(R.string.location_unset))
        }
    }
}
