package org.codenarc.eclipse.jobs

import org.codehaus.jdt.groovy.model.GroovyCompilationUnit
import org.codenarc.analyzer.StringSourceAnalyzer
import org.codenarc.eclipse.CodeNarcMarker
import org.codenarc.eclipse.Logger
import org.codenarc.eclipse.RuleSetProvider
import org.codenarc.eclipse.SelectionUtils
import org.codenarc.eclipse.plugin.preferences.PreferenceAccessor
import org.codenarc.eclipse.plugin.preferences.PreferenceConstants
import org.codenarc.results.Results
import org.codenarc.ruleset.RuleSet
import org.eclipse.core.resources.IFile
import org.eclipse.core.resources.IMarker
import org.eclipse.core.resources.IResource
import org.eclipse.core.runtime.CoreException
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Status
import org.eclipse.core.runtime.jobs.Job
import org.eclipse.jdt.core.JavaCore
import org.eclipse.jface.viewers.IStructuredSelection

class CheckCodeJob extends Job {

    private static final Logger log = Logger.instance

    private IProgressMonitor monitor
    private IStructuredSelection selection

    CheckCodeJob(IStructuredSelection selection) {
        super('Check code with CodeNarc')
        this.selection = selection
    }

    IStatus run(IProgressMonitor monitor) {
        this.monitor = monitor

        def files = selectFiles()
        def project = getProjectFromSelection()
        def ruleSet = createRuleSet(project)
        checkFiles(files, ruleSet)

        monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS
    }

    private List<IFile> selectFiles() {
        monitor.beginTask('Selecting files', 1)
        def files = SelectionUtils.getGroovyFiles(selection)
        monitor.worked(1)

        files
    }

    private IResource getProjectFromSelection() {
        def firstResourceWithProject = selection.find{ IResource resource -> resource.project }
        firstResourceWithProject?.project
    }

    private RuleSet createRuleSet(IResource project) {
		def workUnits = 10
        monitor.beginTask('Loading rulesets', workUnits)

        def ruleSet
        if (PreferenceAccessor.hasProjectSpecificSetting(project)) {
            def fileListAsString = PreferenceAccessor.getOverlayedPreferenceValue(project,
                                                                                  PreferenceConstants.RULESET_FILES)
            def paths = fileListAsString.split(File.pathSeparator).collect{ 'file:' + it }
            ruleSet = RuleSetProvider.createRuleSetFromFiles(paths)
        } else {
            ruleSet = RuleSetProvider.createDefaultRuleSet()
        }

        monitor.worked(workUnits)

        ruleSet
    }

    private void checkFiles(List<IFile> files, RuleSet ruleSet) {
        monitor.beginTask('Checking files', files.size())
        for (file in files) {
            if (monitor.isCanceled()) { return }

            monitor.subTask(file.name)
            checkFile(file, ruleSet)
            monitor.worked(1)
        }
        monitor.done()
    }

    private void checkFile(IFile file, RuleSet ruleSet) {
        try {
            file.deleteMarkers(CodeNarcMarker.SUPER_TYPE, true, IResource.DEPTH_INFINITE)

            def results = analyzeSource(file, ruleSet)

            createViolationMarkers(results, file)
        } catch (CoreException e) {
            log.error('Could not create violation marker', e)
        }
    }

    private Results analyzeSource(IFile file, RuleSet ruleSet) {
        GroovyCompilationUnit unit = (GroovyCompilationUnit) JavaCore.createCompilationUnitFrom(file)
        String source = new String(unit.contents)

        def analyzer = new StringSourceAnalyzer(source)

        def begin = System.currentTimeMillis()
        def results = analyzer.analyze(ruleSet)
        def end = System.currentTimeMillis()

        log.info("Analyzing $file.name took ${end - begin} ms")

        results
    }

    private void createViolationMarkers(Results results, IFile file) {
        for (violation in results.violations) {
            def rule = violation.rule

            def markerType = CodeNarcMarker.getMarkerTypeForPriority(rule.priority)
            def markerMessage = rule.name
            if (violation.message) { markerMessage += ": $violation.message" }

            IMarker marker = file.createMarker(markerType)
            marker.setAttribute(IMarker.LINE_NUMBER, violation.lineNumber)
            marker.setAttribute(IMarker.MESSAGE, markerMessage)
        }
    }
}
