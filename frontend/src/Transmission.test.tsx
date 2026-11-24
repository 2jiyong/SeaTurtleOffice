import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import Transmission from './Transmission.tsx'

afterEach(cleanup)

describe('Transmission 접근성', () => {
  it('연출 중에는 깨진 글자를 화면 낭독기에서 숨기고 완성된 문장만 읽게 한다', () => {
    const { container } = render(<Transmission text="아니란다" animate className="ans" />)
    const scrambled = container.querySelector('[aria-hidden="true"]')
    expect(scrambled).not.toBeNull()
    expect(screen.getByText('아니란다').className).toContain('sr-only')
  })

  it('연출이 없으면 문장을 그대로 하나만 보여 준다', () => {
    const { container } = render(<Transmission text="그렇단다" animate={false} />)
    expect(container.querySelector('[aria-hidden="true"]')).toBeNull()
    expect(screen.getAllByText('그렇단다')).toHaveLength(1)
  })
})
