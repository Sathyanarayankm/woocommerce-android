package com.woocommerce.android.ui.main

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.support.design.widget.BottomNavigationView
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentManager
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.Toolbar
import android.view.Menu
import android.view.MenuItem
import com.woocommerce.android.R
import com.woocommerce.android.analytics.AnalyticsTracker
import com.woocommerce.android.extensions.active
import com.woocommerce.android.extensions.disableShiftMode
import com.woocommerce.android.ui.login.LoginActivity
import dagger.android.AndroidInjection
import dagger.android.AndroidInjector
import dagger.android.DispatchingAndroidInjector
import dagger.android.support.HasSupportFragmentInjector
import kotlinx.android.synthetic.main.activity_main.*
import org.wordpress.android.fluxc.model.SiteModel
import org.wordpress.android.login.LoginMode
import javax.inject.Inject

class MainActivity : AppCompatActivity(),
        MainContract.View,
        HasSupportFragmentInjector,
        BottomNavigationView.OnNavigationItemSelectedListener {
    companion object {
        private const val REQUEST_CODE_ADD_ACCOUNT = 100

        private const val MAGIC_LOGIN = "magic-login"
        private const val TOKEN_PARAMETER = "token"
        private const val KEY_POSITION = "key-position"
    }

    @Inject lateinit var fragmentInjector: DispatchingAndroidInjector<Fragment>
    @Inject lateinit var presenter: MainContract.Presenter

    private var navPosition: BottomNavigationPosition = BottomNavigationPosition.DASHBOARD

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        restoreSavedInstanceState(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Set the toolbar
        setSupportActionBar(toolbar as Toolbar)

        // Verify authenticated session
        presenter.takeView(this)
        if (!presenter.userIsLoggedIn()) {
            if (hasMagicLinkLoginIntent()) {
                getAuthTokenFromIntent()?.let { presenter.storeMagicLinkToken(it) }
            } else {
                showLoginScreen()
                return
            }
        }

        setupBottomNavigation()
        initFragment(savedInstanceState)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_action_bar, menu)
        return true
    }

    public override fun onDestroy() {
        presenter.dropView()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle?) {
        // Store the current bottom bar navigation position.
        outState?.putInt(KEY_POSITION, navPosition.id)
        super.onSaveInstanceState(outState)
    }

    private fun restoreSavedInstanceState(savedInstanceState: Bundle?) {
        // Restore the current navigation position
        savedInstanceState?.also {
            val id = it.getInt(KEY_POSITION, BottomNavigationPosition.DASHBOARD.id)
            navPosition = findNavigationPositionById(id)
        }
    }

    /**
     * Prevent the user from exiting the app on back press if nothing is available
     * in the back stack to process.
     */
    override fun onBackPressed() {
        val count = supportFragmentManager.backStackEntryCount

        if (count > 0) {
            supportFragmentManager.popBackStack()
            if (count == 1) {
                supportActionBar?.setDisplayShowHomeEnabled(false)
                supportActionBar?.setDisplayHomeAsUpEnabled(false)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        return when (item!!.itemId) {
            // User clicked the "up" button in the action bar
            android.R.id.home -> {
                onBackPressed()
                true
            }
            R.id.menu_signout -> {
                presenter.logout()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun supportFragmentInjector(): AndroidInjector<Fragment> = fragmentInjector

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_ADD_ACCOUNT -> {
                if (resultCode == Activity.RESULT_OK) {
                    // TODO Launch next screen
                }
            }
        }
    }

    override fun notifyTokenUpdated() {
        if (hasMagicLinkLoginIntent()) {
            AnalyticsTracker.track(AnalyticsTracker.Stat.LOGIN_MAGIC_LINK_SUCCEEDED)
            // TODO Launch next screen
        }
    }

    override fun showLoginScreen() {
        val intent = Intent(this, LoginActivity::class.java)
        LoginMode.WPCOM_LOGIN_ONLY.putInto(intent)
        startActivityForResult(intent, REQUEST_CODE_ADD_ACCOUNT)
        finish()
    }

    override fun updateStoreList(storeList: List<SiteModel>) {
        if (storeList.isEmpty()) {
//            textView.text = "No WooCommerce sites found!"
        } else {
            val siteNameList = """
                |Found stores:
                |
                |${storeList.joinToString("\n\n") {
                "${it.name}\n(${it.url})\nType: ${if (it.isWpComStore) "WordPress.com Store" else "Jetpack Store" }"
            }}
            """.trimMargin()
//            textView.text = siteNameList
        }
    }

    private fun hasMagicLinkLoginIntent(): Boolean {
        val action = intent.action
        val uri = intent.data
        val host = if (uri != null && uri.host != null) uri.host else ""
        return Intent.ACTION_VIEW == action && host.contains(MAGIC_LOGIN)
    }

    private fun getAuthTokenFromIntent(): String? {
        val uri = intent.data
        return uri?.getQueryParameter(TOKEN_PARAMETER)
    }

    // region Bottom Navigation
    private fun setupBottomNavigation() {
        bottom_nav.disableShiftMode()
        bottom_nav.active(navPosition.position)
        bottom_nav.setOnNavigationItemSelectedListener(this)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        navPosition = findNavigationPositionById(item.itemId)
        return switchFragment(navPosition)
    }
    // endregion

    // region Fragment Processing
    private fun initFragment(savedInstanceState: Bundle?) {
        savedInstanceState ?: switchFragment(BottomNavigationPosition.DASHBOARD)
    }

    /**
     * Extension function for retrieving an existing fragment from the [FragmentManager]
     * if one exists, if not, create a new instance of the requested fragment.
     */
    private fun FragmentManager.findFragment(position: BottomNavigationPosition): Fragment {
        return findFragmentByTag(position.getTag()) ?: position.createFragment()
    }

    /**
     * If the user clicked on the already displayed top-level option, pop any child
     * fragments and reset to the main fragment.
     *
     * If the user selected an option not currently selected, pop any child fragments,
     * detach the current top-level fragment and attach the destination top-level fragment.
     *
     * Immediately execute transactions with FragmentManager#executePendingTransactions.
     */
    private fun switchFragment(navPosition: BottomNavigationPosition): Boolean {
        // Remove any child fragments in the back stack
        popChildFragmentBackStack()

        // Grab the requested top-level fragment and load if not already
        // in the current view.
        val fragment = supportFragmentManager.findFragment(navPosition)
        if (!fragment.isAdded) {
            // Remove the active fragment and replace with this newly selected one
            detachParentFragment()
            attachParentFragment(fragment, navPosition.getTag())
            supportFragmentManager.executePendingTransactions()

            return true
        }
        return false
    }

    /**
     * Attach the provided fragment to the fragment container. This should
     * only be used with top-level fragments.
     */
    private fun attachParentFragment(fragment: Fragment, tag: String) {
        supportFragmentManager.popBackStack()
        if (fragment.isDetached) {
            supportFragmentManager.beginTransaction().attach(fragment).commit()
        } else {
            supportFragmentManager.beginTransaction().add(R.id.container, fragment, tag).commit()
        }
        // Set a transition animation for this transaction.
        supportFragmentManager.beginTransaction()
                .setTransition(android.support.v4.app.FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                .commit()
    }

    /**
     * Remove the current fragment from the fragment container. This
     * should only be used with top-level fragments.
     */
    private fun detachParentFragment() {
        supportFragmentManager.findFragmentById(R.id.container)?.also {
            supportFragmentManager.beginTransaction().detach(it).commit()
        }
    }

    /**
     * Pop all child fragments to return to the top-level view.
     */
    private fun popChildFragmentBackStack() {
        while (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate()
        }
        // Reset toolbar status
        supportActionBar?.setDisplayShowHomeEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
    }
    // endregion
}
