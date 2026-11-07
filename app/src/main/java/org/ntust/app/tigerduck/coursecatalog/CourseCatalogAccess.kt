package org.ntust.app.tigerduck.coursecatalog

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.ntust.app.tigerduck.auth.session.AccountSessions

/** What the Add course sheet reaches the catalog through, from a composable that Hilt does not inject. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CourseCatalogAccess {
    fun courseCatalogClient(): CourseCatalogClient

    fun courseCatalogFiles(): CourseCatalogFiles

    fun accountSessions(): AccountSessions
}
