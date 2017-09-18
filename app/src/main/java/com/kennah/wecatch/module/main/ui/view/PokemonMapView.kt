package com.kennah.wecatch.module.main.ui.view

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import android.view.View
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.Toast
import butterknife.BindView
import butterknife.ButterKnife
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.kennah.wecatch.App
import com.kennah.wecatch.R
import com.kennah.wecatch.core.hasPermission
import com.kennah.wecatch.core.helper.LocationHelper
import com.kennah.wecatch.core.utils.CommonUtils
import com.kennah.wecatch.core.utils.LogUtils
import com.kennah.wecatch.core.utils.ResourceUtils
import com.kennah.wecatch.core.utils.TimeUtils
import com.kennah.wecatch.core.withDelay
import com.kennah.wecatch.local.Constant
import com.kennah.wecatch.local.model.Gym
import com.kennah.wecatch.local.model.Pokemon
import com.kennah.wecatch.local.utils.AnimateUtils
import com.kennah.wecatch.local.utils.ColorUtils
import com.kennah.wecatch.module.main.contract.MainContract
import io.reactivex.Flowable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.collections.ArrayList

@SuppressLint("ViewConstructor")
class PokemonMapView @Inject constructor(context: Context,
                                         private val presenter: MainContract.Presenter,
                                         private val locationHelper: LocationHelper) : RelativeLayout(context),
        MainContract.View,
        OnMapReadyCallback,
        GoogleMap.OnMarkerClickListener,
        GoogleMap.InfoWindowAdapter,
        GoogleMap.OnInfoWindowClickListener {

    private val DEFAULT = LatLng(22.296039, 114.172416)

    @BindView(R.id.map)
    lateinit var mViewMap: MapView
    @BindView(R.id.loading)
    lateinit var mImageLoading: ImageView

    private lateinit var mMap: GoogleMap

    private var mPokemonMarkerList: MutableList<Marker> = mutableListOf()
    private var mCacheFromIntent: ArrayList<Pokemon> = ArrayList()
    private var mExpireCheckFlag = true
    private var mScanning = false

    private var mLastKnownLocation: LatLng? = null

    var onStatusChangeListener: ((loading: Boolean) -> Unit)? = null
    var showLoadingIcon: Boolean = true


    init {

        View.inflate(context, R.layout.view_pokemon_map, this)
        ButterKnife.bind(this)

        presenter.attach(this)

        mViewMap.onCreate(null)
        mViewMap.getMapAsync(this)
    }

    fun onResume() {
        mViewMap.onResume()
        mExpireCheckFlag = true
        expireCheck()
    }

    fun onPause() {

        locationHelper.onStop()
        mViewMap.onPause()
        mExpireCheckFlag = false
    }

    fun onDestroy() {
        mViewMap.onDestroy()
        presenter.detach()
    }

    fun scan() {

        if (mScanning) return

        onStatusChangeListener?.invoke(true)
        if (showLoadingIcon) showLoading()
        mExpireCheckFlag = false
        presenter.getPokemon(mMap.projection.visibleRegion.latLngBounds, mMap.cameraPosition.zoom)
        mScanning = true
    }

    fun cache(pokemonList: ArrayList<Pokemon>) {
        mCacheFromIntent.addAll(pokemonList)
    }

    override fun onError(errorCode: Int) {

        onStatusChangeListener?.invoke(false)
        if (showLoadingIcon) hideLoading()
        mScanning = false
        Toast.makeText(context.applicationContext, errorCode.toString(), Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("MissingPermission")
    override fun onMapReady(map: GoogleMap) {
        mMap = map

        locationHelper.onStart { location ->
            mLastKnownLocation = location
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(mLastKnownLocation, 15F))
        }

        mMap.apply {
            moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT, 15f))
            isBuildingsEnabled = false
            isIndoorEnabled = false
            isTrafficEnabled = false
            uiSettings.isMapToolbarEnabled = false
            setInfoWindowAdapter(this@PokemonMapView)
            setOnInfoWindowClickListener(this@PokemonMapView)
            setOnMarkerClickListener(this@PokemonMapView)

            hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) {
                uiSettings.isMyLocationButtonEnabled = true
                isMyLocationEnabled = true
            }
        }

        if (mCacheFromIntent.size > 0) {
            onPokemonFound(mCacheFromIntent, emptyList())
        }
    }

    override fun onPokemonFound(pokemonList: List<Pokemon>, gymList: List<Gym>) {

        mMap.clear()

        LogUtils.debug(this, "${pokemonList.size} on pokemonList")

        Flowable.fromIterable(pokemonList)
                .subscribeOn(Schedulers.computation())
                .onBackpressureBuffer(1000)
                .concatMap {
                    Flowable.just<Pokemon>(it).delay(50, TimeUnit.MILLISECONDS)
                }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ pokemon ->
                    createPokemonMarker(pokemon)
                }, { e->
                    Log.e("CREATE MARKER", "ERROR", e)
                }, {
                    mScanning = false
                    mExpireCheckFlag = true
                    expireCheck()

                    onStatusChangeListener?.invoke(false)
                    if (showLoadingIcon) hideLoading()
                    if (mCacheFromIntent.size > 0) mCacheFromIntent.clear()
                })

        Flowable.fromIterable(gymList)
                .subscribeOn(Schedulers.computation())
                .onBackpressureBuffer(1000)
                .concatMap {
                    Flowable.just<Gym>(it).delay(50, TimeUnit.MILLISECONDS)
                }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { gym ->
                    val gymMarker = GymMarker(context)
                    gymMarker.bind(gym)

                    val location = gym.location ?: arrayOf(0.0, 0.0)

                    val options = MarkerOptions().position(LatLng(location[1], location[0]))

                    when (gym.raidLevel) {
                        4 or 5 -> options.zIndex(999F)
                        else -> { }
                    }

                    options.icon(BitmapDescriptorFactory.fromBitmap(CommonUtils.getBitmapFromView(gymMarker)))

                    val marker: Marker = mMap.addMarker(options)
                    marker.tag = gym
                }

    }

    override fun onMarkerClick(marker: Marker): Boolean {

        val zoom = mMap.cameraPosition.zoom
        val cameraUpdate = CameraUpdateFactory
                .newLatLngZoom(
                        LatLng(marker.position.latitude + 120 / Math.pow(2.toDouble(), zoom.toDouble()), marker.position.longitude),
                        zoom
                )
        mMap.animateCamera(cameraUpdate, 500, null)

        marker.showInfoWindow()

        return true
    }

    override fun getInfoContents(marker: Marker): View? {

        return when (marker.tag) {
            is Pokemon -> {
                val pokemon = marker.tag as Pokemon
                val window = MapPokemonWindow(context)
                window.apply {
                    bind(pokemon, marker)
                }
                window
            }
            is Gym -> {
                val gym = marker.tag as Gym
                val window = MapGymWindow(context)
                window.apply {
                    bind(gym, marker)
                }
                window
            }
            else -> null
        }
    }

    override fun getInfoWindow(marker: Marker): View? {
        return null
    }

    override fun onInfoWindowClick(marker: Marker) {
        val message = String.format(Locale.getDefault(), "%f,%f", marker.position.latitude, marker.position.longitude)
        CommonUtils.saveToClipboard(context.applicationContext, message)
    }

    private fun createPokemonMarker(pokemon: Pokemon) {
        val options = MarkerOptions()
                .position(LatLng(pokemon.latitude, pokemon.longitude))

        if (pokemon.pokemonId in Constant.DEFAULT_RARE_SEARCH) {
            options.zIndex(999F)
        }

        val resource = ResourceUtils.getDrawableResource(context, "pkm_${pokemon.pokemonId}")

        if (pokemon.iv >= 80) {

            val color = ColorUtils.getPokemonColor(pokemon.iv)

            val bm = BitmapFactory.decodeResource(resources, resource)
            options.icon(BitmapDescriptorFactory.fromBitmap(CommonUtils.highlightImage(bm, color)))
        } else {
            options.icon(BitmapDescriptorFactory.fromResource(ResourceUtils.getDrawableResource(context, "pkm_${pokemon.pokemonId}")))
        }

        val marker: Marker = mMap.addMarker(options)
        marker.tag = pokemon
        mPokemonMarkerList.add(marker)
    }

    private fun expireCheck() {

        withDelay(5000) {
            if (mExpireCheckFlag) {

                if (mPokemonMarkerList.isNotEmpty()) {

                    mPokemonMarkerList.filter {
                        val pokemon = it.tag as? Pokemon
                        TimeUtils.isExpired(pokemon?.expireTime)
                    }.forEach {
                        it.remove()
                        mPokemonMarkerList.remove(it)
                    }
                }

                expireCheck()
            }
        }
    }


    private fun hideLoading() {
        mImageLoading.animation = null
        mImageLoading.visibility = View.GONE
    }

    private fun showLoading() {

        mImageLoading.visibility = View.VISIBLE


        mImageLoading.startAnimation(AnimateUtils.rotateAnimate())
    }
}