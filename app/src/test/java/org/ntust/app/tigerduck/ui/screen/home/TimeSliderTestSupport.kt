package org.ntust.app.tigerduck.ui.screen.home

/** The classes among the time machine's items. */
val TimeSliderViewModel.timeSlots: List<CourseTimeSlot>
    get() = items.filterIsInstance<TimeSliderItem.Course>().map { it.slot }

/** The class an item stands for; the test says which items are classes. */
val TimeSliderItem.slot: CourseTimeSlot get() = (this as TimeSliderItem.Course).slot
